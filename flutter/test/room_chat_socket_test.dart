import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/room/room_api.dart';
import 'package:uno_app/features/room/room_chat_socket.dart';

class _TokenStore implements TokenStore {
  @override
  Future<StoredTokens?> read() async => StoredTokens(
    'access',
    'refresh',
    DateTime.utc(2026, 9, 25),
    DateTime.utc(2026, 10, 24),
  );
  @override
  Future<void> write(StoredTokens tokens) async {}
  @override
  Future<void> clear() async {}
}

void main() {
  test('native chat socket authenticates, subscribes, and receives server owned fields', () async {
    const roomId = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86';
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final subscribed = Completer<void>();
    final received = Completer<RoomChatMessage>();
    final peerClosed = Completer<void>();
    server.listen((request) async {
      expect(request.uri.path, '/ws/chat');
      expect(request.headers.value('x-uno-client'), 'APP');
      expect(request.headers.value('authorization'), 'Bearer access');
      final peer = await WebSocketTransformer.upgrade(request);
      peer.listen((raw) {
        final message = jsonDecode(raw as String) as Map<String, dynamic>;
        expect(message, {
          'protocolVersion': 1,
          'type': 'SUBSCRIBE',
          'roomId': roomId,
        });
        peer.add(
          jsonEncode({
            'protocolVersion': 1,
            'type': 'CHAT_SUBSCRIBED',
            'roomId': roomId,
          }),
        );
        peer.add(
          jsonEncode({
            'protocolVersion': 1,
            'type': 'CHAT_MESSAGE',
            'roomId': roomId,
            'item': {
              'id': 'message-id',
              'roomId': roomId,
              'channel': 'TEAM_A',
              'sequence': 7,
              'senderUserId': 'sender-id',
              'senderNickname': 'Alice',
              'clientMessageId': 'client-id',
              'content': 'Hello team',
              'createdAt': '2026-09-24T10:00:00Z',
            },
          }),
        );
      }, onDone: () => peerClosed.complete());
    });

    final client = MockClient((request) async {
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
            'id': 'user-id',
            'email': 'alice@example.com',
            'nickname': 'Alice',
          }),
          200,
        );
      }
      throw StateError('Unexpected HTTP request: ${request.url}');
    });
    final session = AuthSession(
      api: AuthApi(client: client, baseUrl: 'http://127.0.0.1:${server.port}'),
      store: _TokenStore(),
      now: () => DateTime.utc(2026, 9, 24),
    );
    await session.initialize();
    final api = RoomApi(
      session: session,
      client: client,
      baseUrl: 'http://127.0.0.1:${server.port}',
    );
    final socket = RoomChatSocket(
      roomId: roomId,
      api: api,
      onSubscribed: () => subscribed.complete(),
      onMessage: (message) => received.complete(message),
      onDisconnected: () {},
    );
    socket.start();
    await subscribed.future.timeout(const Duration(seconds: 5));
    final message = await received.future.timeout(const Duration(seconds: 5));
    expect(message.channel, 'TEAM_A');
    expect(message.sequence, 7);
    expect(message.senderUserId, 'sender-id');
    expect(message.content, 'Hello team');
    socket.close();
    await peerClosed.future.timeout(const Duration(seconds: 5));
    api.close();
    session.dispose();
    await server.close(force: true);
  });
}
