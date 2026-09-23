import 'dart:async';

import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import 'room_api.dart';

class RoomWaitingPage extends StatefulWidget {
  const RoomWaitingPage({
    super.key,
    required this.api,
    required this.session,
    required this.initialRoom,
    required this.onLeave,
  });
  final RoomApi api;
  final AuthSession session;
  final WaitingRoom initialRoom;
  final VoidCallback onLeave;

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
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
    } finally {
      refreshing = false;
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
                Text(room.canStart ? '所有人已准备，等待后续对局功能接入。' : '等待人齐并全部准备。'),
              ],
            ),
          ),
        ),
        OutlinedButton(
          onPressed: busy ? null : _leave,
          child: const Text('离开房间'),
        ),
        const Text('当前阶段可组房、选队和准备；对局将在后续阶段接入。'),
      ],
    );
  }
}
