import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import '../../shared/game_mode.dart';
import 'room_api.dart';

class RoomEntryPanel extends StatefulWidget {
  const RoomEntryPanel({
    super.key,
    required this.api,
    required this.session,
    required this.mode,
    required this.onOpen,
    required this.onLogin,
    required this.onPendingInvite,
    this.initialInviteCode,
    this.initialError = '',
  });
  final RoomApi api;
  final AuthSession session;
  final GameMode mode;
  final ValueChanged<WaitingRoom> onOpen;
  final VoidCallback onLogin;
  final ValueChanged<String> onPendingInvite;
  final String? initialInviteCode;
  final String initialError;

  @override
  State<RoomEntryPanel> createState() => _RoomEntryPanelState();
}

class _RoomEntryPanelState extends State<RoomEntryPanel> {
  final code = TextEditingController();
  int maxPlayers = 4;
  bool busy = false;
  String error = '';
  WaitingRoom? current;

  @override
  void initState() {
    super.initState();
    code.text = widget.initialInviteCode ?? '';
    error = widget.initialError;
    widget.session.addListener(_sessionChanged);
    _sessionChanged();
  }

  @override
  void dispose() {
    widget.session.removeListener(_sessionChanged);
    code.dispose();
    super.dispose();
  }

  void _sessionChanged() {
    if (widget.session.state == SessionState.authenticated &&
        current == null &&
        !busy) {
      _loadCurrent();
    } else if (widget.session.state != SessionState.authenticated &&
        current != null) {
      setState(() => current = null);
    } else if (mounted) {
      setState(() {});
    }
  }

  Future<void> _loadCurrent() async {
    if (busy) return;
    busy = true;
    try {
      final value = await widget.api.current();
      if (mounted) {
        setState(() {
          current = value;
          if (value != null || widget.initialError.isEmpty) error = '';
        });
      }
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
    } finally {
      busy = false;
    }
  }

  Future<void> _run(Future<WaitingRoom> Function() action) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = '';
    });
    try {
      final room = await action();
      if (mounted) widget.onOpen(room);
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
      try {
        current = await widget.api.current();
      } catch (_) {
        /* keep action error */
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.session.state != SessionState.authenticated) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('登录后创建或加入好友房。'),
              TextField(
                controller: code,
                maxLength: 10,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(labelText: '受邀房间码'),
              ),
              if (error.isNotEmpty)
                Text(error, style: const TextStyle(color: Colors.red)),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: widget.onLogin,
                child: const Text('前往账号入口'),
              ),
              OutlinedButton(
                onPressed: () {
                  final value = code.text.trim().toUpperCase();
                  if (!RegExp(r'^[A-HJ-NP-Z2-9]{10}$').hasMatch(value)) {
                    setState(() => error = '请输入有效的 10 位房间码。');
                    return;
                  }
                  widget.onPendingInvite(value);
                },
                child: const Text('登录后加入该房间'),
              ),
            ],
          ),
        ),
      );
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('好友房', style: Theme.of(context).textTheme.titleLarge),
            if (error.isNotEmpty)
              Text(error, style: const TextStyle(color: Colors.red)),
            if (current != null) ...[
              Text('你已在房间 ${current!.code}'),
              FilledButton(
                onPressed: () => widget.onOpen(current!),
                child: const Text('回到等待室'),
              ),
            ] else ...[
              if (widget.mode == GameMode.classic)
                DropdownButtonFormField<int>(
                  initialValue: maxPlayers,
                  decoration: const InputDecoration(labelText: '经典局人数上限'),
                  items: [2, 3, 4, 5, 6]
                      .map(
                        (n) => DropdownMenuItem(value: n, child: Text('$n 人')),
                      )
                      .toList(),
                  onChanged: busy
                      ? null
                      : (value) => setState(() => maxPlayers = value ?? 4),
                )
              else
                const Text('2v2 固定 4 人，分 A、B 两队。'),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: busy
                    ? null
                    : () => _run(
                        () => widget.api.create(
                          widget.mode == GameMode.classic
                              ? 'CLASSIC'
                              : 'TEAM_2V2',
                          widget.mode == GameMode.classic ? maxPlayers : 4,
                        ),
                      ),
                child: const Text('创建好友房'),
              ),
              const Divider(height: 32),
              TextField(
                controller: code,
                maxLength: 10,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(labelText: '10 位房间码'),
              ),
              OutlinedButton(
                onPressed: busy
                    ? null
                    : () {
                        final value = code.text.trim().toUpperCase();
                        if (!RegExp(r'^[A-HJ-NP-Z2-9]{10}$').hasMatch(value)) {
                          setState(() => error = '请输入有效的 10 位房间码。');
                          return;
                        }
                        _run(() => widget.api.join(value));
                      },
                child: const Text('加入房间'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
