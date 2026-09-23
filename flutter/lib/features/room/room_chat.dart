import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import 'room_api.dart';

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
  final List<RoomChatMessage> messages = [];
  Timer? timer;
  int cursor = 0;
  String? pollingScope;
  String scope = 'ROOM';
  int generation = 0;
  bool sending = false;
  bool visible = true;
  String? retryId;
  String? retryContent;
  String error = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
    unawaited(_refresh(latest: true));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    visible = state == AppLifecycleState.resumed;
    if (visible) unawaited(_refresh());
  }

  void _merge(List<RoomChatMessage> incoming) {
    final known = messages.map((message) => message.id).toSet();
    messages.addAll(incoming.where((message) => known.add(message.id)));
    messages.sort((a, b) => a.sequence.compareTo(b.sequence));
    if (messages.length > 100) messages.removeRange(0, messages.length - 100);
  }

  Future<void> _refresh({bool latest = false}) async {
    final requestedScope = scope;
    final requestedGeneration = generation;
    if (pollingScope == requestedScope || !visible || !mounted) return;
    pollingScope = requestedScope;
    try {
      final page = await widget.api.messages(
        widget.roomId,
        after: latest ? 0 : cursor,
        latest: latest,
        scope: requestedScope,
      );
      if (!mounted || generation != requestedGeneration) return;
      setState(() {
        _merge(page.items);
        cursor = max(cursor, page.nextSequence);
        error = '';
      });
    } catch (failure) {
      if (mounted && generation == requestedGeneration) {
        setState(() => error = '$failure');
      }
    } finally {
      if (pollingScope == requestedScope) pollingScope = null;
    }
  }

  void _selectScope(String next) {
    if (scope == next || sending) return;
    setState(() {
      scope = next;
      generation++;
      messages.clear();
      cursor = 0;
      retryId = null;
      retryContent = null;
      draft.clear();
      error = '';
    });
    unawaited(_refresh(latest: true));
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
    final content = retryContent ?? draft.text.trim();
    if (content.isEmpty || content.runes.length > 500) {
      setState(() => error = '请输入 1–500 个字符。');
      return;
    }
    final id = retryId ?? _newId();
    setState(() {
      retryId = id;
      retryContent = content;
      sending = true;
      error = '';
    });
    try {
      final saved = await widget.api.sendMessage(
        widget.roomId,
        id,
        content,
        scope: scope,
      );
      if (!mounted) return;
      setState(() {
        _merge([saved]);
        retryId = null;
        retryContent = null;
        draft.clear();
      });
    } catch (failure) {
      if (mounted) setState(() => error = '$failure');
    } finally {
      if (mounted) setState(() => sending = false);
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
                  child: const Text('房间文字'),
                ),
                OutlinedButton(
                  onPressed: sending ? null : () => _selectScope('TEAM'),
                  child: const Text('队伍文字'),
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
              itemCount: messages.length,
              itemBuilder: (context, index) {
                final message = messages[index];
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
          if (error.isNotEmpty)
            Text(
              error,
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
