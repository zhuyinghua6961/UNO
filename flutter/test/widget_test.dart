import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uno_app/app.dart';
import 'package:uno_app/features/room/room_preview_page.dart';
import 'package:uno_app/shared/game_mode.dart';

void main() {
  testWidgets('Lobby shows real room entry before the optional preview', (
    tester,
  ) async {
    await tester.pumpWidget(const UnoApp());
    final entry = find.text('登录后创建或加入好友房。');
    expect(entry, findsOneWidget);
    expect(tester.getTopLeft(entry).dy, lessThan(600));
    await tester.scrollUntilVisible(
      find.text('看看牌桌'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.ensureVisible(find.text('看看牌桌'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('看看牌桌'));
    await tester.pumpAndSettle();
    expect(find.text('经典自由局 · 布局预览'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Preview never opens the microphone', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: RoomPreviewPage(mode: GameMode.team2v2)),
      ),
    );
    await tester.scrollUntilVisible(
      find.text('预览页不开麦'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    final button = tester.widget<FilledButton>(
      find.byWidgetPredicate((widget) => widget is FilledButton),
    );
    expect(button.onPressed, isNull);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Classic mode has no team channel', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: RoomPreviewPage(mode: GameMode.classic)),
      ),
    );
    expect(find.widgetWithText(ChoiceChip, '队伍'), findsNothing);
  });
}
