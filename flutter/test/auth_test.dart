import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';

class MemoryTokenStore implements TokenStore {
  StoredTokens? tokens;
  int writes = 0;
  bool failClear = false;
  @override
  Future<StoredTokens?> read() async => tokens;
  @override
  Future<void> write(StoredTokens value) async {
    tokens = value;
    writes++;
  }

  @override
  Future<void> clear() async {
    if (failClear) throw StateError('secure storage unavailable');
    tokens = null;
  }
}

http.Response jsonResponse(int status, Object value) => http.Response(
  jsonEncode(value),
  status,
  headers: {'content-type': 'application/json'},
);

Map<String, Object> grant(String access, String refresh) => {
  'user': {'id': 'same-user-id', 'email': 'a@example.com', 'nickname': 'Alice'},
  'accessToken': access,
  'refreshToken': refresh,
  'tokenType': 'Bearer',
  'expiresAt': '2026-09-23T13:15:00Z',
  'refreshExpiresAt': '2026-10-23T12:00:00Z',
};

void main() {
  final now = DateTime.utc(2026, 9, 23, 12);

  test(
    'native registration, login, protected identity and logout contract',
    () async {
      final calls = <http.Request>[];
      final client = MockClient((request) async {
        calls.add(request);
        switch (request.url.path) {
          case '/api/auth/status':
            return jsonResponse(200, {
              'service': 'identity-service',
              'loginAvailable': true,
              'registrationAvailable': true,
            });
          case '/api/auth/register':
            expect(jsonDecode(request.body)['email'], 'a@example.com');
            return jsonResponse(202, {'message': 'accepted'});
          case '/api/auth/verify-email':
            return http.Response('', 204);
          case '/api/auth/login':
            expect(
              request.headers['content-type'],
              contains('application/json'),
            );
            return jsonResponse(200, grant('access-1', 'refresh-1'));
          case '/api/users/me':
            expect(request.headers['authorization'], 'Bearer access-1');
            return jsonResponse(200, grant('a', 'b')['user']!);
          case '/api/users/me/profile':
            expect(request.headers['authorization'], 'Bearer access-1');
            expect(jsonDecode(request.body)['nickname'], 'New Alice');
            return jsonResponse(200, {
              'id': 'same-user-id', 'email': 'a@example.com', 'nickname': 'New Alice',
            });
          case '/api/auth/logout':
            expect(request.headers['authorization'], 'Bearer access-1');
            return http.Response('', 204);
        }
        return http.Response('', 404);
      });
      final api = AuthApi(client: client, baseUrl: 'http://localhost:29080');
      final store = MemoryTokenStore();
      final session = AuthSession(api: api, store: store, now: () => now);
      await session.initialize();
      expect(session.state, SessionState.guest);
      await session.register('a@example.com', 'long-enough-password', 'Alice');
      await session.verify('x' * 43);
      await session.login('a@example.com', 'long-enough-password');
      expect(session.user?.id, 'same-user-id');
      expect(store.tokens?.refreshToken, 'refresh-1');
      expect(store.writes, 1);
      expect((await session.withAccess(api.me)).id, 'same-user-id');
      await session.updateNickname('New Alice');
      expect(session.user?.nickname, 'New Alice');
      expect(store.tokens?.accessToken, 'access-1');
      await session.logout();
      expect(session.state, SessionState.guest);
      expect(store.tokens, isNull);
      for (final request in calls) {
        expect(request.headers['x-uno-client'], 'APP');
        expect(request.headers.containsKey('cookie'), isFalse);
        expect(request.headers.containsKey('origin'), isFalse);
      }
      session.dispose();
      api.close();
    },
  );

  test(
    'restart restores and rotates one refresh token before reading profile',
    () async {
      final store = MemoryTokenStore()
        ..tokens = StoredTokens(
          'expired',
          'refresh-1',
          DateTime.utc(2026, 9, 23, 11),
          DateTime.utc(2026, 10, 23),
        );
      var refreshes = 0;
      final api = AuthApi(
        baseUrl: 'http://localhost:29080',
        client: MockClient((request) async {
          switch (request.url.path) {
            case '/api/auth/status':
              return jsonResponse(200, {
                'service': 'identity-service',
                'loginAvailable': true,
                'registrationAvailable': true,
              });
            case '/api/auth/refresh':
              refreshes++;
              expect(jsonDecode(request.body)['token'], 'refresh-1');
              return jsonResponse(200, grant('access-2', 'refresh-2'));
            case '/api/users/me':
              expect(request.headers['authorization'], 'Bearer access-2');
              return jsonResponse(200, grant('a', 'b')['user']!);
          }
          return http.Response('', 404);
        }),
      );
      final session = AuthSession(api: api, store: store, now: () => now);
      await session.initialize();
      expect(session.state, SessionState.authenticated);
      expect(session.user?.id, 'same-user-id');
      expect(store.tokens?.refreshToken, 'refresh-2');
      expect(refreshes, 1);
      session.dispose();
      api.close();
    },
  );

  test('concurrent unauthorized requests share one refresh and use the new access token', () async {
    final store = MemoryTokenStore()
      ..tokens = StoredTokens(
        'old-access',
        'old-refresh',
        DateTime.utc(2026, 9, 23, 13),
        DateTime.utc(2026, 10, 23),
      );
    var refreshes = 0;
    final gate = Completer<void>();
    final api = AuthApi(
      baseUrl: 'http://localhost:29080',
      client: MockClient((request) async {
        if (request.url.path == '/api/auth/refresh') {
          refreshes++;
          await gate.future;
          return jsonResponse(200, grant('new-access', 'new-refresh'));
        }
        if (request.url.path == '/api/auth/status') {
          return jsonResponse(200, {
            'service': 'identity-service',
            'loginAvailable': true,
            'registrationAvailable': true,
          });
        }
        if (request.url.path == '/api/users/me') {
          return jsonResponse(200, grant('a', 'b')['user']!);
        }
        return http.Response('', 404);
      }),
    );
    final session = AuthSession(api: api, store: store, now: () => now);
    await session.initialize();
    Future<String> call(String access) async {
      if (access == 'old-access') {
        await Future<void>.delayed(const Duration(milliseconds: 1));
        throw const AuthFailure(401, 'EXPIRED', 'expired');
      }
      return access;
    }

    final first = session.withAccess(call);
    final second = session.withAccess(call);
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(refreshes, 1);
    gate.complete();
    expect(await first, 'new-access');
    expect(await second, 'new-access');
    expect(store.tokens?.refreshToken, 'new-refresh');
    session.dispose();
    api.close();
  });

  test(
    'revoked refresh clears storage; network failure preserves it',
    () async {
      for (final status in [401, 503]) {
        final store = MemoryTokenStore()
          ..tokens = StoredTokens(
            'expired',
            'refresh-1',
            DateTime.utc(2026, 9, 23, 11),
            DateTime.utc(2026, 10, 23),
          );
        final api = AuthApi(
          baseUrl: 'http://localhost:29080',
          client: MockClient((request) async {
            if (request.url.path == '/api/auth/status') {
              return jsonResponse(200, {
                'service': 'identity-service',
                'loginAvailable': true,
                'registrationAvailable': true,
              });
            }
            return jsonResponse(status, {'code': 'AUTH_UNAVAILABLE'});
          }),
        );
        final session = AuthSession(api: api, store: store, now: () => now);
        await session.initialize();
        expect(store.tokens == null, status == 401);
        expect(
          session.state,
          status == 401 ? SessionState.guest : SessionState.unavailable,
        );
        session.dispose();
        api.close();
      }
    },
  );

  test('logout network failure keeps credentials for retry', () async {
    final store = MemoryTokenStore()
      ..tokens = StoredTokens(
        'access',
        'refresh',
        DateTime.utc(2026, 9, 23, 13),
        DateTime.utc(2026, 10, 23),
      );
    final api = AuthApi(
      baseUrl: 'http://localhost:29080',
      client: MockClient((request) async {
        if (request.url.path == '/api/auth/status') {
          return jsonResponse(200, {
            'service': 'identity-service',
            'loginAvailable': true,
            'registrationAvailable': true,
          });
        }
        if (request.url.path == '/api/users/me') {
          return jsonResponse(200, grant('a', 'b')['user']!);
        }
        return jsonResponse(503, {'code': 'AUTH_UNAVAILABLE'});
      }),
    );
    final session = AuthSession(api: api, store: store, now: () => now);
    await session.initialize();
    await expectLater(session.logout(), throwsA(isA<AuthFailure>()));
    expect(session.state, SessionState.authenticated);
    expect(store.tokens?.refreshToken, 'refresh');
    session.dispose();
    api.close();
  });

  test('wrong password never enters an authenticated state', () async {
    final store = MemoryTokenStore();
    final api = AuthApi(
      baseUrl: 'http://localhost:29080',
      client: MockClient((request) async {
        if (request.url.path == '/api/auth/status') {
          return jsonResponse(200, {
            'service': 'identity-service',
            'loginAvailable': true,
            'registrationAvailable': true,
          });
        }
        return jsonResponse(401, {'code': 'INVALID_CREDENTIALS'});
      }),
    );
    final session = AuthSession(api: api, store: store, now: () => now);
    await session.initialize();
    await expectLater(
      session.login('a@example.com', 'bad-password'),
      throwsA(isA<AuthFailure>()),
    );
    expect(session.state, SessionState.guest);
    expect(session.user, isNull);
    expect(store.tokens, isNull);
    expect(session.error, contains('无效'));
    session.dispose();
    api.close();
  });

  test(
    'revoked logout is hidden even if secure storage deletion fails',
    () async {
      final store = MemoryTokenStore()
        ..tokens = StoredTokens(
          'access',
          'refresh',
          DateTime.utc(2026, 9, 23, 13),
          DateTime.utc(2026, 10, 23),
        );
      final api = AuthApi(
        baseUrl: 'http://localhost:29080',
        client: MockClient((request) async {
          switch (request.url.path) {
            case '/api/auth/status':
              return jsonResponse(200, {
                'service': 'identity-service',
                'loginAvailable': true,
                'registrationAvailable': true,
              });
            case '/api/users/me':
              return jsonResponse(200, grant('a', 'b')['user']!);
            case '/api/auth/logout':
              return http.Response('', 204);
          }
          return http.Response('', 404);
        }),
      );
      final session = AuthSession(api: api, store: store, now: () => now);
      await session.initialize();
      store.failClear = true;
      await expectLater(session.logout(), throwsA(isA<AuthFailure>()));
      expect(session.state, SessionState.guest);
      expect(session.user, isNull);
      expect(session.error, contains('安全存储'));
      session.dispose();
      api.close();
    },
  );
}
