import 'dart:async';

import 'package:flutter/material.dart';

import '../auth/auth_api.dart';
import '../auth/auth_session.dart';
import 'livekit_voice_transport.dart';
import 'voice_api.dart';
import 'voice_device_permission.dart';
import 'voice_transport.dart';

enum TeamVoiceState { idle, joining, joined, muted, reconnecting, error }

class TeamVoicePanel extends StatefulWidget {
  const TeamVoicePanel({
    super.key,
    required this.matchId,
    required this.session,
    this.api,
    this.transportFactory,
    this.devicePermission,
  });

  final String matchId;
  final AuthSession session;
  final VoiceApi? api;
  final VoiceTransport Function()? transportFactory;
  final VoiceDevicePermission? devicePermission;

  @override
  State<TeamVoicePanel> createState() => _TeamVoicePanelState();
}

class _TeamVoicePanelState extends State<TeamVoicePanel>
    with WidgetsBindingObserver {
  late final VoiceApi api;
  late final bool ownsApi;
  late final VoiceTransport transport;
  late final VoiceDevicePermission devicePermission;
  StreamSubscription<VoiceEvent>? subscription;
  TeamVoiceState voiceState = TeamVoiceState.idle;
  bool available = false;
  bool busy = false;
  bool playbackBlocked = false;
  String speaking = '';
  String error = '';
  String notice = '';
  int generation = 0;

  @override
  void initState() {
    super.initState();
    ownsApi = widget.api == null;
    api = widget.api ?? VoiceApi(session: widget.session);
    transport = widget.transportFactory?.call() ?? LiveKitVoiceTransport();
    devicePermission =
        widget.devicePermission ?? const NativeVoiceDevicePermission();
    subscription = transport.events.listen(_onEvent);
    widget.session.addListener(_sessionChanged);
    WidgetsBinding.instance.addObserver(this);
    unawaited(_loadAvailability());
  }

  Future<void> _loadAvailability() async {
    try {
      final enabled = await api.available();
      if (mounted) setState(() => available = enabled);
    } catch (_) {
      if (mounted) setState(() => error = '暂时无法确认语音服务状态。');
    }
  }

  void _sessionChanged() {
    if (widget.session.state != SessionState.authenticated &&
        voiceState != TeamVoiceState.idle) {
      unawaited(_leave());
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed &&
        voiceState != TeamVoiceState.idle) {
      unawaited(_leave(message: '已在后台退出语音，返回牌桌后可重新加入。'));
    }
  }

  @override
  void didUpdateWidget(covariant TeamVoicePanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.matchId != widget.matchId) unawaited(_leave());
    if (oldWidget.session != widget.session) {
      oldWidget.session.removeListener(_sessionChanged);
      widget.session.addListener(_sessionChanged);
    }
  }

  void _onEvent(VoiceEvent event) {
    if (!mounted) return;
    switch (event.kind) {
      case VoiceEventKind.reconnecting:
        setState(() => voiceState = TeamVoiceState.reconnecting);
      case VoiceEventKind.reconnected:
        if (voiceState == TeamVoiceState.reconnecting) {
          setState(
            () => voiceState = transport.microphoneEnabled
                ? TeamVoiceState.joined
                : TeamVoiceState.muted,
          );
        }
      case VoiceEventKind.disconnected:
        unawaited(_leave(message: '语音连接已断开，请重新加入。', failed: true));
      case VoiceEventKind.speaking:
        setState(() => speaking = event.detail);
      case VoiceEventKind.playbackBlocked:
        setState(() => playbackBlocked = true);
    }
  }

  String _message(Object failure) {
    if (failure is AuthFailure) return failure.message;
    final detail = failure.toString().toLowerCase();
    if (detail.contains('permission') || detail.contains('denied')) {
      return '麦克风权限被拒绝，请在系统设置中允许后重试。';
    }
    if (detail.contains('notfound') || detail.contains('no device')) {
      return '没有找到可用的麦克风。';
    }
    return '队友语音连接失败，请检查麦克风、网络和媒体服务后重试。';
  }

  Future<void> _join() async {
    if (!available ||
        busy ||
        ![TeamVoiceState.idle, TeamVoiceState.error].contains(voiceState)) {
      return;
    }
    final current = ++generation;
    setState(() {
      busy = true;
      voiceState = TeamVoiceState.joining;
      error = '';
      notice = '';
    });
    try {
      final bluetoothReady = await devicePermission.prepareBluetooth();
      if (!mounted || current != generation) return;
      if (!bluetoothReady) {
        setState(() => notice = '蓝牙权限未开放；可继续尝试使用手机扬声器。');
      }
      final grant = await api.token(widget.matchId);
      if (!mounted || current != generation) return;
      await transport.join(grant);
      if (!mounted || current != generation) {
        await transport.leave();
        return;
      }
      setState(() => voiceState = TeamVoiceState.joined);
    } catch (failure) {
      if (mounted && current == generation) {
        setState(() {
          voiceState = TeamVoiceState.error;
          error = _message(failure);
        });
      }
      if (current == generation) {
        await transport.leave().catchError((Object _) {});
      }
    } finally {
      if (mounted && current == generation) setState(() => busy = false);
    }
  }

  Future<void> _microphone() async {
    if (busy ||
        ![TeamVoiceState.joined, TeamVoiceState.muted].contains(voiceState)) {
      return;
    }
    final current = generation;
    final enable = voiceState == TeamVoiceState.muted;
    setState(() {
      busy = true;
      error = '';
    });
    try {
      await transport.microphone(enable);
      if (mounted && current == generation) {
        setState(
          () => voiceState = enable
              ? TeamVoiceState.joined
              : TeamVoiceState.muted,
        );
      }
    } catch (failure) {
      if (mounted && current == generation) {
        setState(() => error = _message(failure));
      }
    } finally {
      if (mounted && current == generation) setState(() => busy = false);
    }
  }

  Future<void> _resumeAudio() async {
    try {
      await transport.resumeAudio();
      if (mounted) setState(() => playbackBlocked = false);
    } catch (_) {
      if (mounted) setState(() => error = '声音播放受限，请再点一次开启收听。');
    }
  }

  Future<void> _leave({String? message, bool failed = false}) async {
    generation++;
    if (mounted) {
      setState(() {
        voiceState = failed ? TeamVoiceState.error : TeamVoiceState.idle;
        busy = false;
        speaking = '';
        playbackBlocked = false;
        error = '';
        if (message != null) notice = message;
      });
    }
    try {
      await transport.leave();
    } catch (_) {
      /* SDK disposal still runs in dispose */
    }
  }

  @override
  void dispose() {
    generation++;
    widget.session.removeListener(_sessionChanged);
    WidgetsBinding.instance.removeObserver(this);
    unawaited(subscription?.cancel());
    unawaited(transport.dispose());
    if (ownsApi) api.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final connected = [
      TeamVoiceState.joined,
      TeamVoiceState.muted,
      TeamVoiceState.reconnecting,
    ].contains(voiceState);
    final status = switch (voiceState) {
      TeamVoiceState.joining => '正在连接并申请麦克风…',
      TeamVoiceState.joined => '已加入 · 麦克风开启',
      TeamVoiceState.muted => '已加入 · 麦克风关闭',
      TeamVoiceState.reconnecting => '语音正在重连…',
      _ => '麦克风关闭',
    };
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('队友语音', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 5),
            const Text('只有同队玩家可听见。点击加入后才会申请麦克风。'),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (!connected && voiceState != TeamVoiceState.joining)
                  FilledButton(
                    onPressed: available && !busy ? _join : null,
                    child: Text(available ? '加入队友语音' : '语音暂不可用'),
                  ),
                if (connected || voiceState == TeamVoiceState.joining) ...[
                  OutlinedButton(
                    onPressed:
                        !busy &&
                            voiceState != TeamVoiceState.reconnecting &&
                            connected
                        ? _microphone
                        : null,
                    child: Text(
                      voiceState == TeamVoiceState.muted ? '打开麦克风' : '关闭麦克风',
                    ),
                  ),
                  OutlinedButton(onPressed: _leave, child: const Text('退出语音')),
                ],
                if (playbackBlocked)
                  OutlinedButton(
                    onPressed: _resumeAudio,
                    child: const Text('开启收听'),
                  ),
              ],
            ),
            const SizedBox(height: 7),
            Text('$status${speaking.isEmpty ? '' : ' · 正在说话：$speaking'}'),
            if (notice.isNotEmpty) Text(notice),
            if (error.isNotEmpty)
              Text(
                error,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
          ],
        ),
      ),
    );
  }
}
