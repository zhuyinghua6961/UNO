import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import 'room_api.dart';

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

class _RoomChatState extends State<RoomChat> with WidgetsBindingObserver {
  final draft = TextEditingController();
  final channels = {'ROOM': _ChatChannel(), 'TEAM': _ChatChannel()};
  Timer? timer;
  String scope = 'ROOM';
  int generation = 0;
  bool sending = false;
  bool visible = true;
  String? retryId;
  String? retryContent;
  String sendError = '';

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
  }

  @override
  void didUpdateWidget(covariant RoomChat oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.roomId == widget.roomId &&
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
    draft.clear();
    unawaited(_refresh(channel: 'ROOM', latest: true));
    if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM', latest: true));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    visible = state == AppLifecycleState.resumed;
    if (visible) {
      unawaited(_refresh(channel: 'ROOM'));
      if (widget.teamEnabled) unawaited(_refresh(channel: 'TEAM'));
    }
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

  @override
  void dispose() {
    generation++;
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    draft.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Card(
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
                  subtitle: Text(message.content),
                  trailing: Text(
                    TimeOfDay.fromDateTime(message.createdAt.toLocal())
                        .format(context),
                    style: Theme.of(context).textTheme.labelSmall,
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
