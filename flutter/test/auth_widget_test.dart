import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/app.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';

class MemoryStore implements TokenStore {
  StoredTokens? tokens;
  @override
  Future<StoredTokens?> read() async => tokens;
  @override
  Future<void> write(StoredTokens value) async {
    tokens = value;
  }

  @override
  Future<void> clear() async {
    tokens = null;
  }
}

void main() {
  testWidgets('account page logs in and signs out without exposing tokens', (
    tester,
  ) async {
    final api = AuthApi(
      baseUrl: 'http://localhost:29080',
      client: MockClient((request) async {
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
        if (request.url.path == '/api/auth/login') {
          return http.Response(
            jsonEncode({
              'user': {
                'id': 'user-1',
                'email': 'a@example.com',
                'nickname': 'Alice',
              },
              'accessToken': 'private-access-token',
              'refreshToken': 'private-refresh-token',
              'tokenType': 'Bearer',
              'expiresAt': '2026-09-23T13:15:00Z',
              'refreshExpiresAt': '2026-10-23T12:00:00Z',
            }),
            200,
          );
        }
        if (request.url.path == '/api/auth/logout') {
          return http.Response('', 204);
        }
        return http.Response('', 404);
      }),
    );
    final store = MemoryStore();
    final session = AuthSession(
      api: api,
      store: store,
      now: () => DateTime.utc(2026, 9, 23, 12),
    );
    await tester.pumpWidget(UnoApp(authSession: session));
    await tester.pumpAndSettle();
    await tester.tap(find.text('账号').last);
    await tester.pumpAndSettle();
    expect(find.text('登录'), findsWidgets);
    await tester.enterText(
      find.widgetWithText(TextFormField, '邮箱'),
      'a@example.com',
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, '密码'),
      'long-enough-password',
    );
    await tester.tap(find.widgetWithText(FilledButton, '登录'));
    await tester.pumpAndSettle();
    expect(find.text('用户 ID：user-1'), findsOneWidget);
    expect(find.textContaining('private-access-token'), findsNothing);
    expect(store.tokens?.refreshToken, 'private-refresh-token');
    await tester.tap(find.text('退出登录'));
    await tester.pumpAndSettle();
    expect(find.text('用户 ID：user-1'), findsNothing);
    expect(store.tokens, isNull);
    await tester.pumpWidget(const SizedBox());
    session.dispose();
    api.close();
  });
}
