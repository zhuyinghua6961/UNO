import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uno_app/app.dart';
import 'package:uno_app/features/room/room_preview_page.dart';
import 'package:uno_app/shared/game_mode.dart';

void main() {
  testWidgets('Lobby exposes an honest scaffold preview', (tester) async {
    await tester.pumpWidget(const UnoApp());
    expect(find.text('好朋友，\n就差你这一张。'), findsOneWidget);
    await tester.tap(find.text('看看牌桌'));
    await tester.pumpAndSettle();
    expect(find.text('经典自由局 · 布局预览'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Team voice never activates in a scaffold', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: RoomPreviewPage(mode: GameMode.team2v2)),
      ),
    );
    await tester.scrollUntilVisible(
      find.text('语音接入中 · 麦克风关闭'),
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
