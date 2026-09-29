import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:uno_app/features/auth/auth_api.dart';
import 'package:uno_app/features/auth/auth_session.dart';
import 'package:uno_app/features/match/match_audio_preference.dart';
import 'package:uno_app/features/match/match_api.dart';
import 'package:uno_app/features/match/match_models.dart';
import 'package:uno_app/features/match/match_page.dart';
import 'package:uno_app/features/match/match_socket.dart';
import 'package:uno_app/features/room/room_api.dart';

import 'match_fixture.dart';

class _TokenStore implements TokenStore {
  @override
  Future<StoredTokens?> read() async => null;
  @override
  Future<void> write(StoredTokens tokens) async {}
  @override
  Future<void> clear() async {}
}

class _FakeTransport implements MatchTransport {
  _FakeTransport(this.handlers, this.initial);
  final MatchSocketHandlers handlers;
  final MatchState initial;
  final List<MatchCommand> sent = [];
  bool closed = false;

  @override
  void start() {
    scheduleMicrotask(() {
      handlers.onSnapshot(initial);
      handlers.onStatus(MatchSocketStatus.connected);
    });
  }

  @override
  bool send(MatchCommand command) {
    sent.add(command);
    return true;
  }

  @override
  void close() => closed = true;
}

class _FakeAudioPreference implements MatchAudioPreference {
  bool muted = false;

  @override
  Future<bool> readMuted() async => muted;

  @override
  Future<void> writeMuted(bool value) async => muted = value;
}

void main() {
  testWidgets(
    'table submits one selected card with UNO, applies ACK, ignores stale view and cleans up',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(420, 620));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final session = AuthSession(
        api: AuthApi(
          client: MockClient((_) async => http.Response('', 404)),
          baseUrl: 'http://localhost:29080',
        ),
        store: _TokenStore(),
      );
      session.user = const AuthUser(userId, 'a@example.com', 'Alice');
      session.state = SessionState.authenticated;
      final api = MatchApi(
        session: session,
        client: MockClient((_) async => http.Response('', 404)),
        baseUrl: 'http://localhost:29080',
      );
      final room = WaitingRoom.parse({
        'id': roomId,
        'code': 'ABCDEFGHJK',
        'mode': 'CLASSIC',
        'maxPlayers': 2,
        'hostUserId': userId,
        'state': 'PLAYING',
        'version': 5,
        'expiresAt': '2026-09-24T12:00:00Z',
        'canStart': false,
        'members': [
          {
            'userId': userId,
            'nickname': 'Alice',
            'seat': 0,
            'team': null,
            'ready': false,
          },
          {
            'userId': guestId,
            'nickname': 'Bob',
            'seat': 1,
            'team': null,
            'ready': false,
          },
        ],
      });
      final initial = MatchState.parse(matchSnapshot());
      final audio = _FakeAudioPreference();
      late _FakeTransport transport;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: MatchPage(
              api: api,
              session: session,
              room: room,
              matchId: matchId,
              onBackToRoom: (_) {},
              onLeaveMatch: () {},
              audioPreference: audio,
              transportFactory: (handlers) =>
                  transport = _FakeTransport(handlers, initial),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('你的手牌 · 2 张 · 0 分'), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump(const Duration(milliseconds: 300));
      expect(
        tester.getTopLeft(find.text('你的手牌 · 2 张 · 0 分')).dy,
        greaterThan(0),
      );
      final tableContext = find.textContaining('桌面 红色 1 · 生效红色 · 轮到你 · ');
      expect(tableContext, findsOneWidget);
      expect(
        tester.widget<Text>(tableContext).data,
        matches(RegExp(r'· \d+ 秒$')),
      );
      expect(tester.getTopLeft(tableContext).dy, greaterThan(0));
      expect(tester.getBottomLeft(tableContext).dy, lessThan(620));
      expect(tester.getBottomLeft(find.text('打出选中的牌')).dy, lessThan(620));
      await tester.binding.setSurfaceSize(const Size(1200, 1800));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final cardSize = tester.getSize(find.byKey(const ValueKey('card-2')));
      expect(cardSize.height, greaterThan(0));
      await tester.ensureVisible(find.byKey(const ValueKey('card-2')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('card-2')));
      await tester.pump();
      await tester.ensureVisible(find.text('出牌时喊 UNO'));
      await tester.pump();
      await tester.tap(find.text('出牌时喊 UNO'));
      await tester.pump();
      await tester.ensureVisible(find.text('打出选中的牌'));
      await tester.pump();
      await tester.tap(find.text('打出选中的牌'));
      await tester.pump();
      expect(transport.sent, hasLength(1));
      expect(transport.sent.single.toJson(), containsPair('callUno', true));
      expect(transport.sent.single.cardId, 2);
      await tester.tap(find.text('打出选中的牌'));
      await tester.pump();
      expect(transport.sent, hasLength(1));
      final updated = matchSnapshot(version: 5);
      (updated['view'] as Map<String, Object?>)['ownHand'] = [
        {'id': 104, 'color': null, 'kind': 'WILD', 'number': -1},
      ];
      transport.handlers.onAcknowledged(
        MatchReceipt.parse({
          ...updated,
          'commandId': transport.sent.single.commandId,
          'duplicate': true,
          'appliedVersion': 5,
          'event': 'PLAYED',
          'privateChallengeEvidence': [],
        }),
      );
      await tester.pump();
      expect(find.text('你的手牌 · 1 张 · 0 分'), findsOneWidget);
      transport.handlers.onSnapshot(initial);
      await tester.pump();
      expect(find.text('你的手牌 · 1 张 · 0 分'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const ValueKey('card-104')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('card-104')));
      await tester.pump();
      await tester.ensureVisible(find.text('蓝色'));
      await tester.pump();
      await tester.tap(find.text('蓝色'));
      await tester.pump();
      await tester.ensureVisible(find.text('打出选中的牌'));
      await tester.pump();
      await tester.tap(find.text('打出选中的牌'));
      expect(transport.sent, hasLength(2));
      expect(transport.sent.last.chosenColor, 'BLUE');

      final challenge = matchSnapshot(version: 6, phase: 'DRAW_FOUR_RESPONSE');
      (challenge['view'] as Map<String, Object?>)['canRespondToDrawFour'] =
          true;
      transport.handlers.onSnapshot(MatchState.parse(challenge));
      await tester.pump();
      await tester.ensureVisible(find.text('质疑 +4'));
      await tester.pump();
      await tester.tap(find.text('质疑 +4'));
      expect(transport.sent, hasLength(3));
      expect(transport.sent.last.type, 'CHALLENGE_DRAW_FOUR');
      await tester.ensureVisible(find.text('静音音效'));
      await tester.pump();
      await tester.tap(find.text('静音音效'));
      await tester.pump();
      expect(audio.muted, true);
      expect(find.text('开启音效'), findsOneWidget);
      await tester.ensureVisible(find.text('退出本局'));
      await tester.pump();
      await tester.tap(find.text('退出本局'));
      await tester.pumpAndSettle();
      expect(find.text('退出会立即中断所有人的本局对局，且本局不计胜负。'), findsOneWidget);
      await tester.tap(find.text('继续对局'));
      await tester.pumpAndSettle();
      transport.handlers.onSnapshot(
        MatchState.parse({
          ...matchSnapshot(version: 6),
          'status': 'INTERRUPTED',
          'deadlineAt': null,
          'interruptionReason': 'PLAYER_LEFT',
        }),
      );
      await tester.pump();
      expect(find.text('有玩家主动退出，本局不计胜负。'), findsOneWidget);
      expect(find.text('摸 1 张'), findsNothing);
      expect(transport.sent, hasLength(3));
      await tester.pumpWidget(const SizedBox());
      expect(transport.closed, true);
      api.close();
      session.dispose();
    },
  );
}
