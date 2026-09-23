import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/match/match_api.dart';
import 'package:uno_app/features/match/match_models.dart';

import 'match_fixture.dart';

class _TokenStore implements TokenStore {
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
  test('native match start uses Bearer identity and version, then parses private state', () async {
    final requests = <http.Request>[];
    final client = MockClient((request) async {
      requests.add(request);
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
      if (request.url.path == '/api/rooms/$roomId/start') {
        return http.Response(
          jsonEncode({
            ...matchSnapshot(),
            'matchId': matchId,
            'roomVersion': 8,
          }),
          200,
        );
      }
      if (request.url.path == '/api/rooms/$roomId/match') {
        return http.Response('', 204);
      }
      return http.Response(jsonEncode(matchSnapshot()), 200);
    });
    final session = AuthSession(
      api: AuthApi(client: client, baseUrl: 'http://localhost:29080'),
      store: _TokenStore(),
      now: () => DateTime.utc(2026, 9, 23),
    );
    await session.initialize();
    final api = MatchApi(
      session: session,
      client: client,
      baseUrl: 'http://localhost:29080',
    );
    final started = await api.start(roomId, 7);
    expect(started.matchId, matchId);
    expect(started.state.view.ownHand.map((card) => card.id), [2, 104]);
    expect(await api.current(roomId), isNull);
    expect((await api.state(matchId)).view.version, 4);
    final start = requests.firstWhere((r) => r.url.path.endsWith('/start'));
    expect(jsonDecode(start.body)['expectedVersion'], 7);
    expect(start.headers['authorization'], 'Bearer access');
    expect(start.headers['x-uno-client'], 'APP');
    expect(start.headers.containsKey('cookie'), false);
    api.close();
    session.dispose();
  });

  test('malformed state is rejected and command ids are distinct', () {
    expect(
      () => MatchState.parse({
        ...matchSnapshot(),
        'view': {'rulesVersion': 1, 'ownHand': null},
      }),
      throwsA(isA<MatchDataFailure>()),
    );
    final a = MatchCommand('DRAW', 4);
    final b = MatchCommand('DRAW', 4);
    expect(a.commandId, isNot(b.commandId));
    expect(a.toJson(), containsPair('expectedVersion', 4));
  });
}
