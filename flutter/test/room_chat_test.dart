import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uno_app/features/room/room_api.dart';
import 'package:uno_app/features/room/room_chat.dart';

class ChatRoomApi implements RoomApi {
  final roomMessages = <RoomChatMessage>[];
  final teamMessages = <RoomChatMessage>[];
  final roomAfterValues = <int>[];

  @override
  Future<RoomChatPage> messages(
    String roomId, {
    int after = 0,
    bool latest = false,
    String scope = 'ROOM',
  }) async {
    if (scope == 'ROOM') roomAfterValues.add(after);
    final items = (scope == 'TEAM' ? teamMessages : roomMessages)
        .where((message) => message.sequence > after)
        .toList();
    return RoomChatPage(
      items,
      items.isEmpty ? after : items.last.sequence,
      false,
    );
  }

  @override
  Future<RoomChatMessage> sendMessage(
    String roomId,
    String clientMessageId,
    String content, {
    String scope = 'ROOM',
  }) async => message(scope, 3);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class DelayedChatRoomApi extends ChatRoomApi {
  final oldResponse = Completer<RoomChatPage>();

  @override
  Future<RoomChatPage> messages(
    String roomId, {
    int after = 0,
    bool latest = false,
    String scope = 'ROOM',
  }) {
    if (roomId == 'old-room' && scope == 'ROOM') return oldResponse.future;
    return super.messages(roomId, after: after, latest: latest, scope: scope);
  }
}

RoomChatMessage message(String scope, int sequence) => RoomChatMessage(
  'message-$scope-$sequence',
  'room-id',
  scope == 'ROOM' ? 'ROOM' : 'TEAM_A',
  sequence,
  'user-id',
  'Alice',
  'client-$sequence',
  '$scope message $sequence',
  DateTime.utc(2026, 9, 24),
);

void main() {
  testWidgets('inactive chat channel shows unread messages until opened', (
    tester,
  ) async {
    final api = ChatRoomApi()..roomMessages.add(message('ROOM', 1));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: RoomChat(roomId: 'room-id', api: api, teamEnabled: true),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('ROOM message 1'), findsOneWidget);
    expect(find.textContaining('条未读'), findsNothing);

    api.teamMessages.add(message('TEAM', 2));
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(find.text('队伍文字 · 1 条未读'), findsOneWidget);
    await tester.tap(find.text('队伍文字 · 1 条未读'));
    await tester.pump();
    expect(find.text('TEAM message 2'), findsOneWidget);
    expect(find.textContaining('条未读'), findsNothing);

    api.roomMessages.add(message('ROOM', 3));
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(find.text('房间文字 · 1 条未读'), findsOneWidget);
    await tester.tap(find.text('房间文字 · 1 条未读'));
    await tester.pump();
    expect(find.text('ROOM message 3'), findsOneWidget);
    expect(find.textContaining('条未读'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'room change clears cached messages and ignores a late old reply',
    (tester) async {
      final api = DelayedChatRoomApi()..roomMessages.add(message('ROOM', 2));
      Widget table(String roomId) => MaterialApp(
        home: Scaffold(
          body: RoomChat(roomId: roomId, api: api),
        ),
      );
      await tester.pumpWidget(table('old-room'));
      await tester.pumpWidget(table('new-room'));
      await tester.pump();
      expect(find.text('ROOM message 2'), findsOneWidget);

      api.oldResponse.complete(RoomChatPage([message('ROOM', 1)], 1, false));
      await tester.pump();
      expect(find.text('ROOM message 1'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('send reply does not skip an intervening server message', (
    tester,
  ) async {
    final api = ChatRoomApi()..roomMessages.add(message('ROOM', 1));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: RoomChat(roomId: 'room-id', api: api),
        ),
      ),
    );
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'My message');
    await tester.tap(find.text('发送'));
    await tester.pump();
    expect(find.text('ROOM message 3'), findsOneWidget);

    api.roomMessages.addAll([message('ROOM', 2), message('ROOM', 3)]);
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(api.roomAfterValues.last, 1);
    expect(find.text('ROOM message 2'), findsOneWidget);
    expect(find.byType(ListTile), findsNWidgets(3));
    await tester.pumpWidget(const SizedBox());
  });
}
