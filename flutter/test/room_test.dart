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
