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

const enabled = bool.fromEnvironment('UNO_LOCAL_IOS_TEAM_E2E');
const voiceEnabled = bool.fromEnvironment('UNO_LOCAL_IOS_TEAM_VOICE_E2E');
const apiBase = String.fromEnvironment('API_BASE_URL');
const roomCode = String.fromEnvironment('UNO_TEAM_ROOM_CODE');
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
  for (var attempt = 0; attempt < 40; attempt++) {
    final response = await http.get(Uri.parse('$mailpitBase/api/v1/messages'));
    expect(response.statusCode, 200);
    final messages =
        (jsonDecode(response.body) as Map<String, dynamic>)['messages'] as List;
    for (final raw in messages) {
      final item = raw as Map<String, dynamic>;
      final recipients = item['To'] as List? ?? [];
      if (!recipients.any((to) => (to as Map)['Address'] == email)) continue;
      final detail = await http.get(
        Uri.parse('$mailpitBase/api/v1/message/${item['ID']}'),
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
  throw TestFailure('local verification email was not delivered');
}

Future<void> _waitFor(
  WidgetTester tester,
  bool Function() condition,
  String label, {
  int attempts = 120,
}) async {
  for (var attempt = 0; attempt < attempts; attempt++) {
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

bool _playable(MatchView view, MatchCard card) {
  if (card.color == null || card.color == view.activeColor) return true;
  final top = view.topCard;
  return top.color != null &&
      card.kind == top.kind &&
      (card.kind != 'NUMBER' || card.number == top.number);
}

Map<String, Object?> _automaticAction(MatchView view) {
  switch (view.phase) {
    case 'INITIAL_WILD_COLOR':
      return {'type': 'CHOOSE_INITIAL_COLOR', 'chosenColor': 'RED'};
    case 'DRAW_FOUR_RESPONSE':
      return {'type': 'ACCEPT_DRAW_FOUR'};
    case 'TURN':
    case 'AFTER_DRAW':
      for (final card in view.ownHand) {
        if (view.phase == 'AFTER_DRAW' && card.id != view.drawnCardId) continue;
        if (!_playable(view, card)) continue;
        return {
          'type': 'PLAY',
          'cardId': card.id,
          if (card.color == null) 'chosenColor': 'RED',
          'callUno': view.ownHand.length == 2,
        };
      }
      return {'type': view.phase == 'TURN' ? 'DRAW' : 'PASS'};
    default:
      throw TestFailure('unexpected iOS automatic phase ${view.phase}');
  }
}

Future<void> _submit(
  AuthSession session,
  String matchId,
  MatchState state,
) async {
  final response = await session.withAccess(
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
        ..._automaticAction(state.view),
      }),
    ),
  );
  expect(
    response.statusCode,
    200,
    reason: 'iOS automatic turn: ${response.body}',
  );
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('iOS joins three Web players, acts and sees 2v2 settlement', (
    tester,
  ) async {
    expect(apiBase, isNotEmpty);
    expect(roomCode, matches(RegExp(r'^[A-HJ-NP-Z2-9]{10}$')));
    final authApi = AuthApi(baseUrl: apiBase);
    final session = AuthSession(api: authApi, store: _MemoryTokens());
    final rooms = RoomApi(session: session, baseUrl: apiBase);
    final matchApi = MatchApi(session: session, baseUrl: apiBase);
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox());
      rooms.close();
      matchApi.close();
      authApi.close();
      session.dispose();
    });

    final email = 'uno-ios-team-${_id()}@example.test';
    final password = 'Team-${_id()}-Pass1!';
    await authApi.register(email, password, 'iOS Teammate');
    await authApi.verify(await _verificationToken(email));
    await tester.pumpWidget(UnoApp(authSession: session));
    await _waitFor(
      tester,
      () => session.state == SessionState.guest,
      'guest account',
    );
    await _tap(tester, find.text('账号').last);
    await _waitFor(
      tester,
      () => find.widgetWithText(TextFormField, '邮箱').evaluate().isNotEmpty,
      'login form',
    );
    await tester.enterText(find.widgetWithText(TextFormField, '邮箱'), email);
    await tester.enterText(find.widgetWithText(TextFormField, '密码'), password);
    await _tap(tester, find.widgetWithText(FilledButton, '登录'));
    await _waitFor(
      tester,
      () => session.state == SessionState.authenticated,
      'authenticated account',
    );

    await _tap(tester, find.text('大厅').last);
    await tester.scrollUntilVisible(
      find.widgetWithText(TextField, '10 位房间码'),
      220,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.enterText(find.widgetWithText(TextField, '10 位房间码'), roomCode);
    await _tap(tester, find.widgetWithText(OutlinedButton, '加入房间'));
    await _waitFor(
      tester,
      () => find.text('等待室').evaluate().isNotEmpty,
      'waiting room',
    );
    final joinedRoom = await rooms.current();
    expect(joinedRoom, isNotNull);
    var room = joinedRoom!;
    expect(room.mode, 'TEAM_2V2');
    expect(room.members.length, 4);
    final self = room.members.singleWhere(
      (member) => member.userId == session.user!.id,
    );
    expect(self.seat, 3);
    if (self.team != 'B') {
      await _tap(tester, find.text('加入 B 队'));
      room = await rooms.get(room.id);
    }
    for (var attempt = 0; attempt < 160; attempt++) {
      room = await rooms.get(room.id);
      if (room.members
          .where((member) => member.userId != session.user!.id)
          .every((member) => member.ready)) {
        break;
      }
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 250)),
      );
    }
    expect(
      room.members
          .where((member) => member.userId != session.user!.id)
          .every((member) => member.ready),
      isTrue,
      reason: 'Web players must prepare before the iOS ready action',
    );
    await _tap(tester, find.text('刷新状态'));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );
    await tester.pump();
    await _tap(tester, find.widgetWithText(FilledButton, '准备'));
    await _waitFor(
      tester,
      () => find.text('取消准备').evaluate().isNotEmpty,
      'ready state',
    );
    await _waitFor(
      tester,
      () => find.text('双人组牌桌').evaluate().isNotEmpty,
      'team table',
      attempts: 320,
    );
    final matchId = (await matchApi.current(room.id))!.matchId;
    await _waitFor(
      tester,
      () => find.text('实时连接').evaluate().isNotEmpty,
      'live game socket',
    );
    if (voiceEnabled) {
      await tester.scrollUntilVisible(
        find.text('队友语音'),
        220,
        scrollable: find.byType(Scrollable).first,
      );
      await _waitFor(
        tester,
        () => find.text('加入队友语音').evaluate().isNotEmpty,
        'available team voice',
      );
      await _tap(tester, find.text('仅收听'));
      try {
        await _waitFor(
          tester,
          () => find.text('已加入 · 麦克风关闭').evaluate().isNotEmpty,
          'live listen-only team channel',
          attempts: 80,
        );
      } on TestFailure {
        final labels = tester
            .widgetList<Text>(find.byType(Text))
            .map((text) => text.data)
            .whereType<String>()
            .take(80)
            .toList();
        throw TestFailure('iOS voice did not join; visible labels: $labels');
      }
      debugPrint('UNO_IOS_VOICE_LISTENING');
      await _tap(tester, find.text('退出语音'));
      await _waitFor(
        tester,
        () => find.text('加入队友语音').evaluate().isNotEmpty,
        'left listen-only channel',
      );
      debugPrint('UNO_IOS_VOICE_LEFT');
      await tester.scrollUntilVisible(
        find.text('双人组牌桌'),
        -220,
        scrollable: find.byType(Scrollable).first,
      );
    }

    var acted = false;
    for (var attempt = 0; attempt < 320; attempt++) {
      final state = await matchApi.state(matchId);
      expect(
        state.status,
        'PLAYING',
        reason: 'iOS must take a turn before settlement',
      );
      if (state.view.players[state.view.currentSeat].userId ==
          session.user!.id) {
        final action = switch (state.view.phase) {
          'TURN' => find.text('摸 1 张'),
          'AFTER_DRAW' => find.text('不出刚摸的牌 · 结束回合'),
          'INITIAL_WILD_COLOR' => find.text('红色'),
          'DRAW_FOUR_RESPONSE' => find.text('接受 · 摸 4 张'),
          _ => throw TestFailure(
            'unexpected iOS turn phase ${state.view.phase}',
          ),
        };
        await _tap(tester, action);
        for (var check = 0; check < 40; check++) {
          final next = await matchApi.state(matchId);
          if (next.view.version > state.view.version) {
            acted = true;
            break;
          }
          await tester.runAsync(
            () => Future<void>.delayed(const Duration(milliseconds: 250)),
          );
        }
        break;
      }
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 250)),
      );
    }
    expect(acted, isTrue, reason: 'iOS UI action must reach the game service');

    for (var attempt = 0; attempt < 800; attempt++) {
      final state = await matchApi.state(matchId);
      if (state.status == 'ENDED' && state.view.phase == 'MATCH_OVER') break;
      if (state.status == 'PLAYING' &&
          state.view.players[state.view.currentSeat].userId ==
              session.user!.id) {
        await _submit(session, matchId, state);
      }
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 250)),
      );
    }
    final finished = await matchApi.state(matchId);
    expect(finished.status, 'ENDED');
    expect(finished.view.phase, 'MATCH_OVER');
    final winningSeat = finished.view.roundWinnerSeat;
    expect(winningSeat, isNotNull);
    final winningTeam = winningSeat!.isEven ? 'A' : 'B';
    final history = await matchApi.history();
    expect(history.items.first.matchId, matchId);
    expect(history.items.first.mode, 'TEAM_2V2');
    expect(history.items.first.result, winningTeam == 'B' ? 'WIN' : 'LOSS');
    await tester.pump(const Duration(seconds: 1));
    await tester.scrollUntilVisible(
      find.textContaining('$winningTeam 队赢得对局'),
      220,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.textContaining('$winningTeam 队赢得对局'), findsOneWidget);
  }, skip: !enabled);
}
