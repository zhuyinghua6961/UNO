import 'package:flutter/material.dart';

import 'features/auth/login_page.dart';
import 'features/lobby/lobby_page.dart';
import 'features/room/room_preview_page.dart';
import 'shared/game_mode.dart';

class UnoApp extends StatefulWidget {
  const UnoApp({super.key});

  @override
  State<UnoApp> createState() => _UnoAppState();
}

class _UnoAppState extends State<UnoApp> {
  int selectedPage = 0;
  GameMode mode = GameMode.classic;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'UNO 好友牌桌',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff55704d)),
        scaffoldBackgroundColor: const Color(0xfff7f5ef),
        useMaterial3: true,
      ),
      home: Scaffold(
        appBar: AppBar(
          title: const Text('UNO · 好友牌桌'),
          backgroundColor: const Color(0xfff7f5ef),
          actions: const [
            Padding(padding: EdgeInsets.all(16), child: Text('开发预览')),
          ],
        ),
        body: SafeArea(
          child: switch (selectedPage) {
            0 => LobbyPage(
              mode: mode,
              onModeChanged: (selected) => setState(() => mode = selected),
              onPreview: () => setState(() => selectedPage = 1),
            ),
            1 => RoomPreviewPage(mode: mode),
            _ => const LoginPage(),
          },
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: selectedPage,
          onDestinationSelected: (selected) =>
              setState(() => selectedPage = selected),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.grid_view_rounded),
              label: '大厅',
            ),
            NavigationDestination(
              icon: Icon(Icons.style_outlined),
              label: '牌桌预览',
            ),
            NavigationDestination(
              icon: Icon(Icons.person_outline),
              label: '账号',
            ),
          ],
        ),
      ),
    );
  }
}
