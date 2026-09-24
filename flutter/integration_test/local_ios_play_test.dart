import 'dart:convert';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:integration_test/integration_test.dart';
import 'package:uno_app/app.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/match/match_api.dart';
import 'package:uno_app/features/match/match_models.dart';
import 'package:uno_app/features/room/room_api.dart';

const enabled = bool.fromEnvironment('UNO_LOCAL_IOS_E2E');
const apiBase = String.fromEnvironment('API_BASE_URL');
const mailpitBase = String.fromEnvironment(
  'MAILPIT_BASE_URL',
  defaultValue: 'http://127.0.0.1:28025',
);

class _MemoryTokens implements TokenStore {
  StoredTokens? value;

  @override
  Future<StoredTokens?> read() async => value;

  @override
  Future<void> write(StoredTokens tokens) async => value = tokens;

  @override
  Future<void> clear() async => value = null;
}

String _id() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

Future<String> _verificationToken(String email) async {
  for (var attempt = 0; attempt < 30; attempt++) {
    final response = await http.get(Uri.parse('$mailpitBase/api/v1/messages'));
    expect(response.statusCode, 200, reason: 'local Mailpit must be reachable');
    final listing = jsonDecode(response.body) as Map<String, dynamic>;
    final messages = listing['messages'] as List<dynamic>;
    for (final raw in messages) {
      final item = raw as Map<String, dynamic>;
      final recipients = item['To'] as List<dynamic>? ?? [];
      if (!recipients.any(
        (to) => (to as Map<String, dynamic>)['Address'] == email,
      )) {
        continue;
      }
      final detail = await http.get(
        Uri.parse(
          '$mailpitBase/api/v1/message/${Uri.encodeComponent(item['ID'] as String)}',
        ),
      );
      expect(detail.statusCode, 200);
      final body = jsonDecode(detail.body) as Map<String, dynamic>;
      final token = RegExp(
        r'^Token: ([A-Za-z0-9_-]{43})$',
        multiLine: true,
      ).firstMatch(body['Text'] as String? ?? '')?.group(1);
      if (token != null) return token;
    }
    await Future<void>.delayed(const Duration(milliseconds: 500));
  }
  throw TestFailure('verification email was not delivered to local Mailpit');
}

Future<(String, String)> _account(AuthApi api, String label) async {
  final email = 'uno-ios-${_id()}@example.test';
  final password = 'Smoke-${_id()}-Pass1!';
  await api.register(email, password, label);
  await api.verify(await _verificationToken(email));
  return (email, password);
}

Future<void> _waitFor(
  WidgetTester tester,
  bool Function() condition,
  String label,
) async {
  for (var attempt = 0; attempt < 80; attempt++) {
    await tester.pump();
    if (condition()) return;
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 250)),
    );
  }
  throw TestFailure('iOS UI did not reach $label');
}

Future<void> _tap(WidgetTester tester, Finder finder) async {
  if (finder.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      finder,
      220,
      scrollable: find.byType(Scrollable).first,
    );
  }
  await tester.ensureVisible(finder);
  await tester.pump();
  await tester.tap(finder);
  await tester.pump();
}

Future<http.Response> _submit(
  AuthSession session,
  String matchId,
  MatchState state,
  Map<String, Object?> action,
) => session.withAccess(
  (token) => http.post(
    Uri.parse('$apiBase/api/matches/$matchId/commands'),
    headers: {
      'X-UNO-Client': 'APP',
      'Authorization': 'Bearer $token',
      'Content-Type': 'application/json',
    },
    body: jsonEncode({
      'protocolVersion': 1,
      'commandId': _id(),
      'expectedVersion': state.view.version,
      ...action,
    }),
  ),
);

Future<void> _guestMove(
  AuthSession session,
  String matchId,
  MatchState state,
) async {
  final view = state.view;
  final type = switch (view.phase) {
    'TURN' => 'DRAW',
    'AFTER_DRAW' => 'PASS',
    'INITIAL_WILD_COLOR' => 'CHOOSE_INITIAL_COLOR',
    'DRAW_FOUR_RESPONSE' => 'ACCEPT_DRAW_FOUR',
    _ => throw TestFailure('unexpected guest phase ${view.phase}'),
  };
  final response = await _submit(session, matchId, state, {
    'type': type,
    if (type == 'CHOOSE_INITIAL_COLOR') 'chosenColor': 'RED',
  });
  expect(
    response.statusCode,
    200,
    reason: 'guest action $type: ${response.body}',
  );
}

bool _playable(MatchView view, MatchCard card) {
  if (card.color == null || card.color == view.activeColor) return true;
  final top = view.topCard;
  return top.color != null &&
      card.kind == top.kind &&
      (card.kind != 'NUMBER' || card.number == top.number);
}

Map<String, Object?> _automaticAction(MatchView view) {
  switch (view.phase) {
    case 'ROUND_OVER':
      return {'type': 'NEXT_ROUND'};
    case 'INITIAL_WILD_COLOR':
      return {'type': 'CHOOSE_INITIAL_COLOR', 'chosenColor': 'RED'};
    case 'DRAW_FOUR_RESPONSE':
      return {'type': 'ACCEPT_DRAW_FOUR'};
    case 'TURN':
    case 'AFTER_DRAW':
      MatchCard? card;
      for (final candidate in view.ownHand) {
        if (view.phase == 'AFTER_DRAW' && candidate.id != view.drawnCardId) {
          continue;
        }
        if (_playable(view, candidate)) {
          card = candidate;
          break;
        }
      }
      if (card == null) {
        return {'type': view.phase == 'TURN' ? 'DRAW' : 'PASS'};
      }
      return {
        'type': 'PLAY',
        'cardId': card.id,
        if (card.color == null) 'chosenColor': 'RED',
        'callUno': view.ownHand.length == 2,
      };
    default:
      throw TestFailure('unexpected automatic phase ${view.phase}');
  }
}

Future<MatchState> _finishMatch(
  String matchId,
  AuthSession hostSession,
  AuthSession guestSession,
  MatchApi hostMatches,
  MatchApi guestMatches,
) async {
  for (var actionNumber = 0; actionNumber < 2500; actionNumber++) {
    final publicState = await hostMatches.state(matchId);
    if (publicState.status == 'ENDED') return publicState;
    expect(publicState.status, 'PLAYING');
    final actorId =
        publicState.view.players[publicState.view.currentSeat].userId;
    final useHost =
        publicState.view.phase == 'ROUND_OVER' ||
        actorId == hostSession.user!.id;
    final session = useHost ? hostSession : guestSession;
    final privateState = useHost
        ? publicState
        : await guestMatches.state(matchId);
    final action = _automaticAction(privateState.view);
    final response = await _submit(session, matchId, privateState, action);
    if (response.statusCode == 409) continue;
    expect(
      response.statusCode,
      200,
      reason: 'automatic action ${action['type']}: ${response.body}',
    );
  }
  throw TestFailure('classic match did not finish within 2500 actions');
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'iOS signs in, plays a real classic match, sees settlement and starts again',
    (tester) async {
      expect(
        apiBase,
        isNotEmpty,
        reason: 'pass API_BASE_URL with --dart-define',
      );
      final hostApi = AuthApi(baseUrl: apiBase);
      final guestApi = AuthApi(baseUrl: apiBase);
      final hostSession = AuthSession(api: hostApi, store: _MemoryTokens());
      final guestSession = AuthSession(api: guestApi, store: _MemoryTokens());
      final hostRooms = RoomApi(session: hostSession, baseUrl: apiBase);
      final guestRooms = RoomApi(session: guestSession, baseUrl: apiBase);
      final hostMatches = MatchApi(session: hostSession, baseUrl: apiBase);
      final guestMatches = MatchApi(session: guestSession, baseUrl: apiBase);
      addTearDown(() async {
        await tester.pumpWidget(const SizedBox());
        hostRooms.close();
        guestRooms.close();
        hostMatches.close();
        guestMatches.close();
        hostApi.close();
        guestApi.close();
        hostSession.dispose();
        guestSession.dispose();
      });

      final hostCredentials = await _account(hostApi, 'iOS Host');
      final guestCredentials = await _account(guestApi, 'iOS Guest');
      await guestSession.initialize();
      await guestSession.login(guestCredentials.$1, guestCredentials.$2);
      await tester.pumpWidget(UnoApp(authSession: hostSession));
      await _waitFor(
        tester,
        () => hostSession.state == SessionState.guest,
        'guest account state',
      );

      await _tap(tester, find.text('账号').last);
      await _waitFor(
        tester,
        () => find.widgetWithText(TextFormField, '邮箱').evaluate().isNotEmpty,
        'login form',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, '邮箱'),
        hostCredentials.$1,
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, '密码'),
        hostCredentials.$2,
      );
      await _tap(tester, find.widgetWithText(FilledButton, '登录'));
      await _waitFor(
        tester,
        () => hostSession.state == SessionState.authenticated,
        'authenticated account',
      );
      expect(find.text('我的账号'), findsOneWidget);

      await _tap(tester, find.text('大厅').last);
      await _tap(tester, find.text('创建好友房'));
      await _waitFor(
        tester,
        () => find.text('等待室').evaluate().isNotEmpty,
        'waiting room',
      );
      final room = await hostRooms.current();
      expect(room, isNotNull);
      var guestRoom = await guestRooms.join(room!.code);
      guestRoom = await guestRooms.ready(guestRoom, true);
      await _tap(tester, find.text('刷新状态'));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 500)),
      );
      await tester.pump();
      await _tap(tester, find.widgetWithText(FilledButton, '准备'));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 500)),
      );
      await tester.pump();
      await _tap(tester, find.text('开始对局'));
      await _waitFor(
        tester,
        () => find.text('经典牌桌').evaluate().isNotEmpty,
        'classic table',
      );

      final matchId = (await hostMatches.current(room.id))!.matchId;
      for (var attempt = 0; attempt < 6; attempt++) {
        final state = await guestMatches.state(matchId);
        final actor = state.view.players[state.view.currentSeat].userId;
        if (actor == hostSession.user!.id) break;
        expect(actor, guestSession.user!.id);
        await _guestMove(guestSession, matchId, state);
      }
      final before = await hostMatches.state(matchId);
      expect(
        before.view.players[before.view.currentSeat].userId,
        hostSession.user!.id,
      );
      await _waitFor(
        tester,
        () => find.text('实时连接').evaluate().isNotEmpty,
        'live game socket',
      );
      final action = switch (before.view.phase) {
        'TURN' => find.text('摸 1 张'),
        'AFTER_DRAW' => find.text('不出刚摸的牌 · 结束回合'),
        'INITIAL_WILD_COLOR' => find.text('红色'),
        'DRAW_FOUR_RESPONSE' => find.text('接受 · 摸 4 张'),
        _ => throw TestFailure('unexpected host phase ${before.view.phase}'),
      };
      await _waitFor(
        tester,
        () => action.evaluate().isNotEmpty,
        'available turn control',
      );
      await _tap(tester, action);
      var progressed = false;
      for (var attempt = 0; attempt < 40; attempt++) {
        final next = await hostMatches.state(matchId);
        if (next.view.version > before.view.version) {
          progressed = true;
          break;
        }
        await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 250)),
        );
      }
      expect(
        progressed,
        isTrue,
        reason: 'iOS UI action must reach the real game service',
      );

      final finished = await _finishMatch(
        matchId,
        hostSession,
        guestSession,
        hostMatches,
        guestMatches,
      );
      expect(finished.view.phase, 'MATCH_OVER');
      final hostHistory = await hostMatches.history();
      final guestHistory = await guestMatches.history();
      expect(hostHistory.items.first.matchId, matchId);
      expect(guestHistory.items.first.matchId, matchId);
      expect(
        {hostHistory.items.first.result, guestHistory.items.first.result},
        {'WIN', 'LOSS'},
      );

      await tester.pump(const Duration(seconds: 1));
      await tester.scrollUntilVisible(
        find.textContaining('赢得了对局'),
        220,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.textContaining('赢得了对局'), findsOneWidget);
      await _tap(tester, find.text('返回等待室 · 再来一局'));
      await _waitFor(
        tester,
        () => find.text('等待室').evaluate().isNotEmpty,
        'returned waiting room',
      );
      guestRoom = await guestRooms.get(room.id);
      guestRoom = await guestRooms.ready(guestRoom, true);
      await _tap(tester, find.text('刷新状态'));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 500)),
      );
      await tester.pump();
      await _tap(tester, find.widgetWithText(FilledButton, '准备'));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 500)),
      );
      await tester.pump();
      await _tap(tester, find.text('开始对局'));
      final secondMatchId = (await hostMatches.current(room.id))!.matchId;
      expect(secondMatchId, isNot(matchId));
      await _waitFor(
        tester,
        () => find.text('经典牌桌').evaluate().isNotEmpty,
        'second classic table',
      );
    },
    skip: !enabled,
  );
}
