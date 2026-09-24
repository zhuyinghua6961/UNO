import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import 'room_api.dart';
import 'room_chat_socket.dart';

class _ChatChannel {
  final List<RoomChatMessage> messages = [];
  int cursor = 0;
  int unread = 0;
  bool initialized = false;
  bool polling = false;
  String error = '';
}

class RoomChat extends StatefulWidget {
  const RoomChat({
    super.key,
    required this.roomId,
    required this.api,
    this.teamEnabled = false,
  });
  final String roomId;
  final RoomApi api;
  final bool teamEnabled;

  @override
  State<RoomChat> createState() => _RoomChatState();
}

class _RoomChatState extends State<RoomChat>
    with WidgetsBindingObserver, AutomaticKeepAliveClientMixin<RoomChat> {
  final draft = TextEditingController();
  final channels = {'ROOM': _ChatChannel(), 'TEAM': _ChatChannel()};
  Timer? timer;
  RoomChatSocket? socket;
  String scope = 'ROOM';
  int generation = 0;
  bool sending = false;
  bool visible = true;
  String? retryId;
  String? retryContent;
  String sendError = '';
  final reportedIds = <String>{};
  String? reportingId;

  @override
  bool get wantKeepAlive => true;

  _ChatChannel get current => channels[scope]!;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      unawaited(_refresh(channel: 'ROOM'));
      if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM'));
    });
    unawaited(_refresh(channel: 'ROOM', latest: true));
    if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM', latest: true));
    _connect();
  }

  @override
  void didUpdateWidget(covariant RoomChat oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.roomId == widget.roomId &&
        oldWidget.api == widget.api &&
        oldWidget.teamEnabled == widget.teamEnabled) {
      return;
    }
    generation++;
    channels['ROOM'] = _ChatChannel();
    channels['TEAM'] = _ChatChannel();
    scope = 'ROOM';
    sending = false;
    retryId = null;
    retryContent = null;
    sendError = '';
    reportedIds.clear();
    reportingId = null;
    draft.clear();
    unawaited(_refresh(channel: 'ROOM', latest: true));
    if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM', latest: true));
    _connect();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    visible = state == AppLifecycleState.resumed;
    if (visible) {
      _connect();
      unawaited(_refresh(channel: 'ROOM'));
      if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM'));
    } else {
      socket?.close();
      socket = null;
    }
  }

  void _connect() {
    socket?.close();
    socket = RoomChatSocket(
      roomId: widget.roomId,
      api: widget.api,
      onSubscribed: () {
        if (!mounted || !visible) return;
        unawaited(_refresh(channel: 'ROOM'));
        if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM'));
      },
      onMessage: (message) {
        if (!mounted || !visible || message.roomId != widget.roomId) return;
        final channel = message.channel == 'ROOM' ? 'ROOM' : 'TEAM';
        if (channel == 'TEAM' && !widget.teamEnabled) return;
        setState(() {
          final state = channels[channel]!;
          final added = _merge(state, [message]);
          if (added > 0 && state.initialized && channel != scope) {
            state.unread += added;
          }
        });
      },
      onDisconnected: () {
        if (!mounted || !visible) return;
        unawaited(_refresh(channel: 'ROOM'));
        if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM'));
      },
    )..start();
  }

  int _merge(_ChatChannel state, List<RoomChatMessage> incoming) {
    final known = state.messages.map((message) => message.id).toSet();
    final added = incoming.where((message) => known.add(message.id)).toList();
    state.messages.addAll(added);
    state.messages.sort((a, b) => a.sequence.compareTo(b.sequence));
    if (state.messages.length > 100) {
      state.messages.removeRange(0, state.messages.length - 100);
    }
    return added.length;
  }

  Future<void> _refresh({required String channel, bool latest = false}) async {
    final state = channels[channel]!;
    final requestedGeneration = generation;
    if (state.polling || !visible || !mounted) return;
    state.polling = true;
    try {
      final page = await widget.api.messages(
        widget.roomId,
        after: latest ? 0 : state.cursor,
        latest: latest,
        scope: channel,
      );
      if (!mounted || generation != requestedGeneration) return;
      setState(() {
        final added = _merge(state, page.items);
        state.cursor = max(state.cursor, page.nextSequence);
        if (state.initialized && channel != scope) state.unread += added;
        state.initialized = true;
        state.error = '';
      });
    } catch (failure) {
      if (mounted && generation == requestedGeneration) {
        setState(() => state.error = '$failure');
      }
    } finally {
      state.polling = false;
    }
  }

  void _selectScope(String next) {
    if (scope == next || sending) return;
    setState(() {
      scope = next;
      current.unread = 0;
      retryId = null;
      retryContent = null;
      draft.clear();
      sendError = '';
    });
    unawaited(_refresh(channel: next, latest: !current.initialized));
  }

  String _newId() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes
        .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
        .join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
        '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }

  Future<void> _send() async {
    if (sending) return;
    final requestedGeneration = generation;
    final content = retryContent ?? draft.text.trim();
    if (content.isEmpty || content.runes.length > 500) {
      setState(() => sendError = '请输入 1–500 个字符。');
      return;
    }
    final id = retryId ?? _newId();
    setState(() {
      retryId = id;
      retryContent = content;
      sending = true;
      sendError = '';
    });
    try {
      final saved = await widget.api.sendMessage(
        widget.roomId,
        id,
        content,
        scope: scope,
      );
      if (!mounted || generation != requestedGeneration) return;
      setState(() {
        _merge(current, [saved]);
        retryId = null;
        retryContent = null;
        draft.clear();
      });
    } catch (failure) {
      if (mounted && generation == requestedGeneration) {
        setState(() => sendError = '$failure');
      }
    } finally {
      if (mounted && generation == requestedGeneration) {
        setState(() => sending = false);
      }
    }
  }

  Future<void> _report(RoomChatMessage message) async {
    if (reportingId != null || reportedIds.contains(message.id)) return;
    final requestedGeneration = generation;
    final roomId = widget.roomId;
    final reason = await showDialog<String>(
      context: context,
      builder: (context) => SimpleDialog(
        title: const Text('举报消息'),
        children: [
          for (final (code, label) in [
            ('ABUSE', '辱骂或骚扰'),
            ('SPAM', '刷屏或垃圾信息'),
            ('OTHER', '其他不当内容'),
          ])
            SimpleDialogOption(
              onPressed: () => Navigator.pop(context, code),
              child: Text(label),
            ),
        ],
      ),
    );
    if (reason == null || !mounted || generation != requestedGeneration) return;
    setState(() => reportingId = message.id);
    try {
      await widget.api.reportMessage(roomId, message.id, reason);
      if (!mounted || generation != requestedGeneration) return;
      setState(() => reportedIds.add(message.id));
    } catch (_) {
      if (mounted && generation == requestedGeneration) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('举报提交失败，请稍后重试。')));
      }
    } finally {
      if (mounted && generation == requestedGeneration) {
        setState(() => reportingId = null);
      }
    }
  }

  @override
  void dispose() {
    generation++;
    timer?.cancel();
    socket?.close();
    WidgetsBinding.instance.removeObserver(this);
    draft.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.teamEnabled)
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton(
                    onPressed: sending ? null : () => _selectScope('ROOM'),
                    child: Text(
                      '房间文字${channels['ROOM']!.unread > 0 ? ' · ${channels['ROOM']!.unread} 条未读' : ''}',
                    ),
                  ),
                  OutlinedButton(
                    onPressed: sending ? null : () => _selectScope('TEAM'),
                    child: Text(
                      '队伍文字${channels['TEAM']!.unread > 0 ? ' · ${channels['TEAM']!.unread} 条未读' : ''}',
                    ),
                  ),
                ],
              ),
            Text(
              scope == 'TEAM' ? '队伍文字' : '房间文字',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Text(
              scope == 'TEAM'
                  ? '仅当前队友可见 · 换队后不读取旧队消息 · 最多 500 字'
                  : '房间成员可见 · 纯文字 · 最多 500 字',
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 180,
              child: ListView.builder(
                itemCount: current.messages.length,
                itemBuilder: (context, index) {
                  final message = current.messages[index];
                  return ListTile(
                    dense: true,
                    title: Text(message.senderNickname),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(message.content),
                        Text(
                          TimeOfDay.fromDateTime(message.createdAt.toLocal())
                              .format(context),
                        ),
                      ],
                    ),
                    trailing: reportedIds.contains(message.id)
                        ? const Icon(Icons.check, semanticLabel: '已举报')
                        : IconButton(
                            tooltip: '举报',
                            icon: const Icon(Icons.flag_outlined),
                            onPressed: reportingId == null
                                ? () => _report(message)
                                : null,
                          ),
                  );
                },
              ),
            ),
            if (sendError.isNotEmpty || current.error.isNotEmpty)
              Text(
                sendError.isNotEmpty ? sendError : current.error,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            TextField(
              controller: draft,
              maxLines: 2,
              maxLength: 500,
              decoration: const InputDecoration(labelText: '消息'),
              onChanged: (value) {
                if (retryContent != null && value.trim() != retryContent) {
                  setState(() {
                    retryId = null;
                    retryContent = null;
                  });
                }
              },
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: FilledButton(
                onPressed: sending ? null : _send,
                child: Text(
                  sending
                      ? '发送中…'
                      : retryId == null
                      ? '发送'
                      : '重试发送',
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
