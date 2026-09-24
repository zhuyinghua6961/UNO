import 'dart:async';

import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import '../match/match_api.dart';
import 'room_api.dart';
import 'room_chat.dart';

class RoomWaitingPage extends StatefulWidget {
  const RoomWaitingPage({
    super.key,
    required this.api,
    required this.matches,
    required this.session,
    required this.initialRoom,
    required this.onLeave,
    required this.onOpenMatch,
    this.autoEnterMatch = true,
  });
  final RoomApi api;
  final MatchApi matches;
  final AuthSession session;
  final WaitingRoom initialRoom;
  final VoidCallback onLeave;
  final ValueChanged<String> onOpenMatch;
  final bool autoEnterMatch;

  @override
  State<RoomWaitingPage> createState() => _RoomWaitingPageState();
}

class _RoomWaitingPageState extends State<RoomWaitingPage>
    with WidgetsBindingObserver {
  late WaitingRoom room;
  Timer? timer;
  bool busy = false;
  bool refreshing = false;
  bool visible = true;
  bool openingMatch = false;
  String error = '';

  @override
  void initState() {
    super.initState();
    room = widget.initialRoom;
    WidgetsBinding.instance.addObserver(this);
    timer = Timer.periodic(const Duration(seconds: 4), (_) => _refresh());
    _refresh();
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    visible = state == AppLifecycleState.resumed;
    if (visible) _refresh();
  }

  Future<void> _refresh() async {
    if (!visible || busy || refreshing) return;
    refreshing = true;
    try {
      final next = await widget.api.get(room.id);
      if (mounted && next.version >= room.version) {
        setState(() {
          room = next;
          error = '';
        });
      }
      if (mounted && next.state == 'PLAYING' && widget.autoEnterMatch) {
        await _discoverMatch();
      }
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
    } finally {
      refreshing = false;
    }
  }

  Future<void> _discoverMatch() async {
    if (openingMatch || !mounted) return;
    openingMatch = true;
    try {
      final current = await widget.matches.current(room.id);
      if (mounted && current != null) widget.onOpenMatch(current.matchId);
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
    } finally {
      openingMatch = false;
    }
  }

  Future<void> _startMatch() async {
    if (busy || !room.canStart) return;
    setState(() {
      busy = true;
      error = '';
    });
    try {
      final started = await widget.matches.start(room.id, room.version);
      if (mounted) widget.onOpenMatch(started.matchId);
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
      try {
        final next = await widget.api.get(room.id);
        if (mounted) setState(() => room = next);
      } catch (_) {
        // Keep the actionable start error.
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _change(Future<WaitingRoom> Function(WaitingRoom) action) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = '';
    });
    try {
      final next = await action(room);
      if (mounted) setState(() => room = next);
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
      try {
        final next = await widget.api.get(room.id);
        if (mounted) setState(() => room = next);
      } catch (_) {
        /* keep the action error */
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _leave() async {
    if (busy) return;
    setState(() {
      busy = true;
      error = '';
    });
    try {
      await widget.api.leave(room.id);
      if (mounted) widget.onLeave();
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final self = room.members
        .where((m) => m.userId == widget.session.user?.id)
        .firstOrNull;
    final host = room.hostUserId == widget.session.user?.id;
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Text('等待室', style: Theme.of(context).textTheme.headlineMedium),
        if (error.isNotEmpty)
          Text(error, style: const TextStyle(color: Colors.red)),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(room.mode == 'TEAM_2V2' ? '默契双人组 · 2v2' : '经典自由局'),
                SelectableText(
                  '房间码：${room.code}',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                Text('有效期至 ${room.expiresAt.toLocal()}'),
                const Text('把房间码发给朋友，对方可以在 Web 或 App 大厅输入加入。'),
                OutlinedButton(onPressed: _refresh, child: const Text('刷新状态')),
              ],
            ),
          ),
        ),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '玩家 ${room.members.length} / ${room.maxPlayers}',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                if (host && room.mode == 'CLASSIC')
                  DropdownButtonFormField<int>(
                    key: ValueKey('${room.id}-${room.maxPlayers}'),
                    initialValue: room.maxPlayers,
                    decoration: const InputDecoration(labelText: '人数上限'),
                    items: [2, 3, 4, 5, 6]
                        .map(
                          (n) => DropdownMenuItem(
                            value: n,
                            enabled: n >= room.members.length,
                            child: Text('$n 人'),
                          ),
                        )
                        .toList(),
                    onChanged: busy
                        ? null
                        : (n) {
                            if (n != null) {
                              _change((r) => widget.api.settings(r, n));
                            }
                          },
                  ),
                for (final member in room.members)
                  ListTile(
                    leading: CircleAvatar(child: Text('${member.seat + 1}')),
                    title: Text(member.nickname),
                    subtitle: Text(
                      '${member.userId == room.hostUserId ? '房主 · ' : ''}${member.team == null ? '' : '${member.team} 队 · '}${member.ready ? '已准备' : '未准备'}',
                    ),
                  ),
                if (self != null && room.state == 'WAITING') ...[
                  FilledButton(
                    onPressed: busy
                        ? null
                        : () =>
                              _change((r) => widget.api.ready(r, !self.ready)),
                    child: Text(self.ready ? '取消准备' : '准备'),
                  ),
                  if (room.mode == 'TEAM_2V2')
                    Wrap(
                      spacing: 8,
                      children: [
                        for (final team in ['A', 'B'])
                          OutlinedButton(
                            onPressed: busy || self.team == team
                                ? null
                                : () =>
                                      _change((r) => widget.api.team(r, team)),
                            child: Text('加入 $team 队'),
                          ),
                      ],
                    ),
                ],
                if (room.state == 'PLAYING') ...[
                  const Text('对局正在进行。'),
                  FilledButton(
                    onPressed: openingMatch ? null : _discoverMatch,
                    child: const Text('继续当前对局'),
                  ),
                ] else ...[
                  Text(room.canStart ? '所有人已准备，房主可以开始对局。' : '等待人齐并全部准备。'),
                  if (host)
                    FilledButton(
                      onPressed: busy || !room.canStart ? null : _startMatch,
                      child: const Text('开始对局'),
                    ),
                ],
              ],
            ),
          ),
        ),
        OutlinedButton(
          onPressed: busy ? null : _leave,
          child: const Text('离开房间'),
        ),
        RoomChat(
          key: ValueKey('${room.id}:${self?.team ?? 'none'}'),
          roomId: room.id,
          api: widget.api,
          teamEnabled: room.mode == 'TEAM_2V2',
        ),
        const Text('经典局、2v2 对局和文字消息已可使用；2v2 牌桌可主动开启队友语音。'),
      ],
    );
  }
}
