import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/room/room_api.dart';
import 'package:uno_app/features/room/room_entry_panel.dart';
import 'package:uno_app/shared/game_mode.dart';

class RoomTokenStore implements TokenStore {
  @override
  Future<StoredTokens?> read() async => StoredTokens(
    'access',
    'refresh',
    DateTime.utc(2026, 9, 24),
    DateTime.utc(2026, 10, 24),
  );
  @override
  Future<void> write(StoredTokens tokens) async {}
  @override
  Future<void> clear() async {}
}

void main() {
  testWidgets('guest keeps an invitation code while moving to login', (
    tester,
  ) async {
    final client = MockClient((_) async => http.Response('', 404));
    final auth = AuthApi(client: client, baseUrl: 'http://localhost:29080');
    final session = AuthSession(api: auth, store: RoomTokenStore());
    final rooms = RoomApi(
      session: session,
      client: client,
      baseUrl: 'http://localhost:29080',
    );
    String? pending;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: RoomEntryPanel(
            api: rooms,
            session: session,
            mode: GameMode.classic,
            onOpen: (_) {},
            onLogin: () {},
            onPendingInvite: (code) => pending = code,
          ),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), 'abcdefghjk');
    await tester.tap(find.text('登录后加入该房间'));
    expect(pending, 'ABCDEFGHJK');
    await tester.pumpWidget(const SizedBox());
    rooms.close();
    session.dispose();
  });

  test(
    'native room calls carry Bearer identity and expected version',
    () async {
      final calls = <http.Request>[];
      final client = MockClient((request) async {
        calls.add(request);
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
              'id': 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc',
              'email': 'a@example.com',
              'nickname': 'Alice',
            }),
            200,
          );
        }
        if (request.url.path == '/api/rooms/current') {
          return http.Response('', 204);
        }
        if (request.url.path.endsWith('/messages')) {
          final team =
              request.url.queryParameters['channel'] == 'TEAM' ||
              (request.method == 'POST' &&
                  jsonDecode(request.body)['channel'] == 'TEAM');
          final message = {
            'id': 'e53572ef-9237-425b-b45a-2d14a017a74c',
            'roomId': 'cae648d7-afaf-4dcd-a492-8bad87cb8c86',
            'channel': team ? 'TEAM_A' : 'ROOM',
            'sequence': 3,
            'senderUserId': 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc',
            'senderNickname': 'Alice',
            'clientMessageId': '4904abda-1a52-49f8-9385-926e36493996',
            'content': '<b>纯文字</b>',
            'createdAt': '2026-09-23T10:00:00Z',
          };
          return http.Response(
            jsonEncode(
              request.method == 'POST'
                  ? message
                  : {
                      'items': [message],
                      'nextSequence': 3,
                      'hasMore': false,
                    },
            ),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }
        if (request.url.path == '/api/rooms' ||
            request.url.path.endsWith('/ready')) {
          return http.Response(
            jsonEncode({
              'id': 'cae648d7-afaf-4dcd-a492-8bad87cb8c86',
              'code': 'ABCDEFGHJK',
              'mode': 'CLASSIC',
              'maxPlayers': 4,
              'hostUserId': 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc',
              'state': 'WAITING',
              'version': 1,
              'expiresAt': '2026-09-24T12:00:00Z',
              'canStart': false,
              'members': [
                {
                  'userId': 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc',
                  'nickname': 'Alice',
                  'seat': 0,
                  'team': null,
                  'ready': false,
                },
              ],
            }),
            200,
          );
        }
        return http.Response('', 404);
      });
      final auth = AuthApi(client: client, baseUrl: 'http://localhost:29080');
      final session = AuthSession(
        api: auth,
        store: RoomTokenStore(),
        now: () => DateTime.utc(2026, 9, 23),
      );
      await session.initialize();
      final rooms = RoomApi(
        session: session,
        client: client,
        baseUrl: 'http://localhost:29080',
      );
      expect(await rooms.current(), isNull);
      final room = await rooms.create('CLASSIC', 4);
      await rooms.ready(room, true);
      final page = await rooms.messages(room.id, latest: true);
      expect(page.nextSequence, 3);
      expect(page.items.single.content, '<b>纯文字</b>');
      final message = await rooms.sendMessage(
        room.id,
        '4904abda-1a52-49f8-9385-926e36493996',
        '<b>纯文字</b>',
      );
      expect(message.senderNickname, 'Alice');
      final historyRequest = calls.firstWhere(
        (request) =>
            request.method == 'GET' && request.url.path.endsWith('/messages'),
      );
      expect(historyRequest.url.queryParameters['latest'], 'true');
      expect(
        jsonDecode(calls.last.body)['clientMessageId'],
        message.clientMessageId,
      );
      final teamPage = await rooms.messages(
        room.id,
        latest: true,
        scope: 'TEAM',
      );
      expect(teamPage.items.single.channel, 'TEAM_A');
      final teamMessage = await rooms.sendMessage(
        room.id,
        '4904abda-1a52-49f8-9385-926e36493996',
        'team',
        scope: 'TEAM',
      );
      expect(teamMessage.channel, 'TEAM_A');
      expect(
        calls
            .where((c) => c.method == 'GET' && c.url.path.endsWith('/messages'))
            .last
            .url
            .queryParameters['channel'],
        'TEAM',
      );
      expect(jsonDecode(calls.last.body)['channel'], 'TEAM');
      expect(
        jsonDecode(
          calls.where((c) => c.url.path.endsWith('/ready')).single.body,
        )['expectedVersion'],
        1,
      );
      for (final request in calls.where(
        (c) => c.url.path.startsWith('/api/rooms'),
      )) {
        expect(request.headers['authorization'], 'Bearer access');
        expect(request.headers['x-uno-client'], 'APP');
        expect(request.headers.containsKey('cookie'), false);
      }
      rooms.close();
      session.dispose();
    },
  );
}
