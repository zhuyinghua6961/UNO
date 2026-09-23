import 'dart:async';

import 'package:flutter/material.dart';

import 'features/auth/login_page.dart';
import 'features/auth/auth_api.dart';
import 'features/auth/auth_session.dart';
import 'features/lobby/lobby_page.dart';
import 'features/room/room_preview_page.dart';
import 'features/room/room_api.dart';
import 'features/room/room_entry_panel.dart';
import 'features/room/room_waiting_page.dart';
import 'shared/game_mode.dart';

class UnoApp extends StatefulWidget {
  const UnoApp({super.key, this.authSession});

  final AuthSession? authSession;

  @override
  State<UnoApp> createState() => _UnoAppState();
}

class _UnoAppState extends State<UnoApp> with WidgetsBindingObserver {
  int selectedPage = 0;
  GameMode mode = GameMode.classic;
  late final AuthSession session;
  late final bool ownsSession;
  late final RoomApi rooms;
  WaitingRoom? activeRoom;
  String? pendingInviteCode;
  String? inviteCodeForEntry;
  String inviteError = '';
  bool joiningInvite = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    ownsSession = widget.authSession == null;
    session =
        widget.authSession ??
        AuthSession(api: AuthApi(), store: SecureTokenStore());
    rooms = RoomApi(session: session);
    session.addListener(_sessionChanged);
    session.initialize();
  }

  void _sessionChanged() {
    if (mounted &&
        session.state != SessionState.authenticated &&
        activeRoom != null) {
      setState(() => activeRoom = null);
    }
    if (session.state == SessionState.authenticated &&
        pendingInviteCode != null &&
        !joiningInvite) {
      unawaited(_joinPendingInvite());
    }
  }

  Future<void> _joinPendingInvite() async {
    final code = pendingInviteCode;
    if (code == null) return;
    joiningInvite = true;
    try {
      final room = await rooms.join(code);
      if (!mounted) return;
      setState(() {
        activeRoom = room;
        pendingInviteCode = null;
        inviteCodeForEntry = null;
        inviteError = '';
        selectedPage = 0;
      });
    } catch (failure) {
      if (!mounted) return;
      setState(() {
        inviteError = '$failure';
        pendingInviteCode = null;
        selectedPage = 0;
      });
    } finally {
      joiningInvite = false;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    session.removeListener(_sessionChanged);
    rooms.close();
    if (ownsSession) {
      session.api.close();
      session.dispose();
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(session.revalidate().catchError((Object _) {}));
    }
  }

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
          actions: [
            ListenableBuilder(
              listenable: session,
              builder: (context, _) => Padding(
                padding: const EdgeInsets.all(16),
                child: Text(session.user?.nickname ?? '开发预览'),
              ),
            ),
          ],
        ),
        body: SafeArea(
          child: switch (selectedPage) {
            0 when activeRoom != null => RoomWaitingPage(
              api: rooms,
              session: session,
              initialRoom: activeRoom!,
              onLeave: () => setState(() => activeRoom = null),
            ),
            0 => LobbyPage(
              mode: mode,
              onModeChanged: (selected) => setState(() => mode = selected),
              onPreview: () => setState(() => selectedPage = 1),
              roomEntry: RoomEntryPanel(
                api: rooms,
                session: session,
                mode: mode,
                initialInviteCode: inviteCodeForEntry,
                initialError: inviteError,
                onOpen: (room) => setState(() => activeRoom = room),
                onLogin: () => setState(() => selectedPage = 2),
                onPendingInvite: (code) => setState(() {
                  pendingInviteCode = code;
                  inviteCodeForEntry = code;
                  inviteError = '';
                  selectedPage = 2;
                }),
              ),
            ),
            1 => RoomPreviewPage(mode: mode),
            _ => LoginPage(session: session),
          },
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: selectedPage,
          onDestinationSelected: (selected) => setState(() {
            selectedPage = selected;
            if (selected == 0) activeRoom = null;
          }),
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
