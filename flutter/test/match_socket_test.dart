import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/match/match_api.dart';
import 'package:uno_app/features/match/match_models.dart';
import 'package:uno_app/features/match/match_socket.dart';

import 'match_fixture.dart';

class _TokenStore implements TokenStore {
  @override
  Future<StoredTokens?> read() async => StoredTokens(
    'access',
    'refresh',
    DateTime.utc(2026, 9, 23, 1),
    DateTime.utc(2026, 10, 24),
  );
  @override
  Future<void> write(StoredTokens tokens) async {}
  @override
  Future<void> clear() async {}
}

void main() {
  test('native socket rechecks state, sends deliberate command, and stops after takeover', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final messages = <String>[];
    var connections = 0;
    var refreshes = 0;
    var now = DateTime.utc(2026, 9, 23);
    final connected = Completer<void>();
    final takenOver = Completer<void>();
    final acknowledged = Completer<MatchReceipt>();
    final errors = <String>[];
    WebSocket? activePeer;
    server.listen((request) async {
      expect(request.uri.path, '/ws/game');
      expect(request.headers.value('x-uno-client'), 'APP');
      expect(
        request.headers.value('authorization'),
        connections < 2 ? 'Bearer access' : 'Bearer renewed-access',
      );
      final peer = await WebSocketTransformer.upgrade(request);
      connections++;
      final connectionNumber = connections;
      peer.listen((data) {
        final message = jsonDecode(data as String) as Map<String, dynamic>;
        messages.add(message['type'] as String);
        if (message['type'] == 'SUBSCRIBE') {
          expect(message['matchId'], matchId);
          if (connectionNumber == 1) {
            peer.close(WebSocketStatus.policyViolation, 'RATE_LIMITED');
          } else if (connectionNumber == 2) {
            now = DateTime.utc(2026, 9, 23, 2);
            peer.close(WebSocketStatus.policyViolation, 'SESSION_EXPIRED');
          } else {
            activePeer = peer;
            peer.add(
              jsonEncode({
                'protocolVersion': 1,
                'type': 'MATCH_SNAPSHOT',
                'matchId': matchId,
                ...matchSnapshot(),
              }),
            );
          }
        } else if (message['type'] == 'COMMAND') {
          final command = message['command'] as Map<String, dynamic>;
          peer.add(
            jsonEncode({
              'protocolVersion': 1,
              'type': 'COMMAND_ACK',
              'matchId': matchId,
              'result': {
                ...matchSnapshot(version: 5),
                'commandId': command['commandId'],
                'duplicate': false,
                'appliedVersion': 5,
                'event': 'DREW',
                'privateChallengeEvidence': [],
              },
            }),
          );
        }
      });
    });
    final client = MockClient((request) async {
      if (request.url.path == '/api/auth/refresh') {
        refreshes++;
        return http.Response(
          jsonEncode({
            'user': {
              'id': userId,
              'email': 'a@example.com',
              'nickname': 'Alice',
            },
            'accessToken': 'renewed-access',
            'refreshToken': 'renewed-refresh',
            'tokenType': 'Bearer',
            'expiresAt': '2026-09-24T02:00:00Z',
            'refreshExpiresAt': '2026-10-24T00:00:00Z',
          }),
          200,
        );
      }
      if (request.url.path == '/api/auth/status') {
        return http.Response(
          jsonEncode({
            'service': 'identity-service',
            'loginAvailable': true,
            'registrationAvailable': true,
          }),
          200,
        );
      }
      if (request.url.path == '/api/users/me') {
        return http.Response(
          jsonEncode({
            'id': userId,
            'email': 'a@example.com',
            'nickname': 'Alice',
          }),
          200,
        );
      }
      return http.Response(jsonEncode(matchSnapshot()), 200);
    });
    final session = AuthSession(
      api: AuthApi(client: client, baseUrl: 'http://localhost:${server.port}'),
      store: _TokenStore(),
      now: () => now,
    );
    await session.initialize();
    final api = MatchApi(
      session: session,
      client: client,
      baseUrl: 'http://127.0.0.1:${server.port}',
    );
    final socket = MatchSocket(
      matchId: matchId,
      api: api,
      session: session,
      onStatus: (status) {
        if (status == MatchSocketStatus.connected && !connected.isCompleted) {
          connected.complete();
        } else if (status == MatchSocketStatus.takenOver && !takenOver.isCompleted) {
          takenOver.complete();
        }
      },
      onSnapshot: (_) {},
      onAcknowledged: (receipt) => acknowledged.complete(receipt),
      onRejected: (_, _) => errors.add('rejected'),
      onError: errors.add,
    );
    expect(socket.send(MatchCommand('DRAW', 4)), false);
    socket.start();
    await connected.future.timeout(const Duration(seconds: 5));
    expect(connections, 3);
    expect(refreshes, 1);
    expect(messages, ['SUBSCRIBE', 'SUBSCRIBE', 'SUBSCRIBE']);
    final command = MatchCommand('DRAW', 4);
    expect(socket.send(command), true);
    final receipt = await acknowledged.future.timeout(
      const Duration(seconds: 5),
    );
    expect(receipt.commandId, command.commandId);
    expect(receipt.state.view.version, 5);
    expect(messages, ['SUBSCRIBE', 'SUBSCRIBE', 'SUBSCRIBE', 'COMMAND']);
    expect(errors, isEmpty);
    await activePeer!.close(4001, 'TAKEN_OVER');
    await takenOver.future.timeout(const Duration(seconds: 5));
    expect(socket.send(MatchCommand('DRAW', 5)), false);
    await Future<void>.delayed(const Duration(milliseconds: 750));
    expect(connections, 3);
    socket.close();
    api.close();
    session.dispose();
    await server.close(force: true);
  });
}
