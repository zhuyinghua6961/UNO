import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../auth/auth_session.dart';
import '../room/room_api.dart';
import '../room/room_chat.dart';
import 'match_audio_preference.dart';
import 'match_api.dart';
import 'match_models.dart';
import 'match_socket.dart';

class MatchPage extends StatefulWidget {
  const MatchPage({
    super.key,
    required this.api,
    required this.session,
    required this.room,
    required this.matchId,
    required this.onBackToRoom,
    this.transportFactory,
    this.audioPreference,
  });

  final MatchApi api;
  final AuthSession session;
  final WaitingRoom room;
  final String matchId;
  final ValueChanged<bool> onBackToRoom;
  final MatchTransport Function(MatchSocketHandlers handlers)? transportFactory;
  final MatchAudioPreference? audioPreference;

  @override
  State<MatchPage> createState() => _MatchPageState();
}

class _MatchPageState extends State<MatchPage> with WidgetsBindingObserver {
  MatchState? state;
  MatchSocketStatus status = MatchSocketStatus.connecting;
  MatchTransport? socket;
  MatchCommand? pending;
  List<MatchCard> evidence = const [];
  int? selectedCardId;
  String? chosenColor;
  bool callUno = false;
  bool muted = false;
  bool audioChanged = false;
  bool visible = true;
  String error = '';
  String notice = '';
  int generation = 0;
  late Timer clock;
  DateTime now = DateTime.now();
  late final MatchAudioPreference audioPreference;
  late final RoomApi chatApi;

  MatchView? get view => state?.view;
  int get ownSeat =>
      view?.players.indexWhere((p) => p.userId == widget.session.user?.id) ??
      -1;
  bool get myTurn => view != null && view!.currentSeat == ownSeat;
  bool get canSend =>
      view != null && status == MatchSocketStatus.connected && pending == null;
  bool get activeTurn =>
      myTurn && (view?.phase == 'TURN' || view?.phase == 'AFTER_DRAW');
  bool get isTeam => widget.room.mode == 'TEAM_2V2';
  String? get ownTeam => ownSeat < 0
      ? null
      : ownSeat.isEven
      ? 'A'
      : 'B';

  @override
  void initState() {
    super.initState();
    audioPreference = widget.audioPreference ?? SecureMatchAudioPreference();
    chatApi = RoomApi(session: widget.session);
    WidgetsBinding.instance.addObserver(this);
    clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() => now = DateTime.now());
    });
    _open();
    unawaited(_restoreAudioPreference());
  }

  Future<void> _restoreAudioPreference() async {
    try {
      final value = await audioPreference.readMuted();
      if (mounted && !audioChanged) setState(() => muted = value);
    } catch (_) {
      if (mounted) setState(() => notice = '无法读取音效偏好，本次使用默认设置。');
    }
  }

  Future<void> _toggleAudioPreference() async {
    final next = !muted;
    setState(() {
      audioChanged = true;
      muted = next;
    });
    try {
      await audioPreference.writeMuted(next);
    } catch (_) {
      if (mounted) setState(() => notice = '无法保存音效偏好，本次设置仍有效。');
    }
  }

  @override
  void didUpdateWidget(covariant MatchPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.matchId != widget.matchId) _open();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycle) {
    visible = lifecycle == AppLifecycleState.resumed;
    if (visible) {
      _open();
    } else {
      generation++;
      socket?.close();
      socket = null;
      if (mounted) {
        setState(() {
          status = MatchSocketStatus.disconnected;
          if (pending != null) {
            pending = null;
            notice = '刚才的操作未获确认；返回后请核对牌面。';
          }
        });
      }
    }
  }

  void _open() {
    final current = ++generation;
    socket?.close();
    socket = null;
    setState(() {
      state = null;
      status = MatchSocketStatus.connecting;
      pending = null;
      selectedCardId = null;
      chosenColor = null;
      evidence = const [];
      error = '';
    });
    final handlers = MatchSocketHandlers(
      onStatus: (next) {
        if (!mounted || current != generation) return;
        setState(() {
          status = next;
          if (next == MatchSocketStatus.connected) error = '';
          if (next != MatchSocketStatus.connected && pending != null) {
            pending = null;
            notice = '刚才的操作未获确认；重连后请核对牌面并按需重试。';
          }
          if (next == MatchSocketStatus.unauthorized) {
            state = null;
            error = '对局会话已失效，请重新登录。';
          } else if (next == MatchSocketStatus.takenOver) {
            notice = '此牌局已在同一账号的另一窗口或设备接管。';
          }
        });
      },
      onSnapshot: (next) {
        if (mounted && current == generation) _accept(next);
      },
      onAcknowledged: (receipt) {
        if (!mounted || current != generation) return;
        setState(() {
          if (pending?.commandId == receipt.commandId) pending = null;
          notice = receipt.duplicate ? '已恢复上次操作的确认。' : '操作已由服务器确认。';
          error = '';
        });
        _accept(receipt.state);
        setState(() => evidence = receipt.privateChallengeEvidence);
        if (!muted && !receipt.duplicate) {
          unawaited(SystemSound.play(SystemSoundType.click));
        }
      },
      onRejected: (commandId, code) {
        if (!mounted ||
            current != generation ||
            pending?.commandId != commandId) {
          return;
        }
        setState(() {
          pending = null;
          error = code == 'MATCH_CONFLICT'
              ? '牌局已变化，正在同步最新状态。'
              : code == 'TURN_EXPIRED'
              ? '操作窗口已结束，正在同步服务器裁决。'
              : '服务器拒绝此动作（$code），请检查牌局状态。';
        });
        unawaited(_sync(clearError: false));
      },
      onError: (message) {
        if (!mounted || current != generation) return;
        setState(() => error = message);
        unawaited(_sync(clearError: false));
      },
    );
    final channel =
        widget.transportFactory?.call(handlers) ??
        MatchSocket(
          matchId: widget.matchId,
          api: widget.api,
          session: widget.session,
          onStatus: handlers.onStatus,
          onSnapshot: handlers.onSnapshot,
          onAcknowledged: handlers.onAcknowledged,
          onRejected: handlers.onRejected,
          onError: handlers.onError,
        );
    socket = channel;
    channel.start();
  }

  void _accept(MatchState next) {
    final previous = state;
    if (previous != null && next.view.version < previous.view.version) return;
    setState(() {
      if (pending != null && next.view.version > pending!.expectedVersion) {
        pending = null;
        notice = '牌局已推进，已按服务器状态同步。';
      }
      if (previous == null || next.view.version > previous.view.version) {
        selectedCardId = null;
        chosenColor = null;
        callUno = false;
        evidence = const [];
      }
      state = next;
    });
  }

  Future<void> _sync({bool clearError = true}) async {
    final current = generation;
    try {
      final next = await widget.api.state(widget.matchId);
      if (!mounted || current != generation) return;
      _accept(next);
      if (clearError) setState(() => error = '');
    } catch (failure) {
      if (mounted && current == generation) {
        setState(() => error = '$failure');
      }
    }
  }

  void _send(
    String type, {
    int? cardId,
    String? color,
    String? targetUserId,
    bool? sayUno,
  }) {
    if (!canSend) return;
    final command = MatchCommand(
      type,
      view!.version,
      cardId: cardId,
      chosenColor: color,
      targetUserId: targetUserId,
      callUno: sayUno,
    );
    setState(() {
      pending = command;
      error = '';
      notice = '';
    });
    if (socket?.send(command) != true) {
      setState(() {
        pending = null;
        error = '连接尚未就绪，请等待重连后再试。';
      });
    }
  }

  void _playSelected() {
    final current = view;
    if (!activeTurn || current == null) return;
    final card = current.ownHand
        .where((item) => item.id == selectedCardId)
        .firstOrNull;
    if (card == null ||
        (current.phase == 'AFTER_DRAW' && current.drawnCardId != card.id)) {
      return;
    }
    if (card.color == null && chosenColor == null) {
      setState(() => error = '请先选择万能牌生效的颜色。');
      return;
    }
    _send(
      'PLAY',
      cardId: card.id,
      color: card.color == null ? chosenColor : null,
      sayUno: current.ownHand.length == 2 && callUno,
    );
  }

  String _playerName(String userId) =>
      widget.room.members
          .where((member) => member.userId == userId)
          .map((member) => member.nickname)
          .firstOrNull ??
      '玩家 ${userId.substring(0, 6)}';

  String _colorName(String? color) => switch (color) {
    'RED' => '红色',
    'YELLOW' => '黄色',
    'GREEN' => '绿色',
    'BLUE' => '蓝色',
    _ => '待选色',
  };

  Color _color(String color) => switch (color) {
    'RED' => const Color(0xffd86456),
    'YELLOW' => const Color(0xffe8c45b),
    'GREEN' => const Color(0xff70ab78),
    _ => const Color(0xff75a8d2),
  };

  Widget _colorChoices({required bool submitImmediately}) => Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      for (final color in cardColors)
        ChoiceChip(
          label: Text(_colorName(color)),
          selected: chosenColor == color,
          selectedColor: _color(color).withValues(alpha: 0.35),
          onSelected: !canSend
              ? null
              : (_) {
                  if (submitImmediately) {
                    _send('CHOOSE_INITIAL_COLOR', color: color);
                  } else {
                    setState(() => chosenColor = color);
                  }
                },
        ),
    ],
  );

  Widget _action(String label, VoidCallback action, {bool filled = false}) =>
      filled
      ? FilledButton(onPressed: canSend ? action : null, child: Text(label))
      : OutlinedButton(onPressed: canSend ? action : null, child: Text(label));

  Widget _controls(MatchView current) {
    final winner = current.roundWinnerSeat == null
        ? null
        : current.players[current.roundWinnerSeat!];
    final winningTeam = winner == null
        ? null
        : winner.seat.isEven
        ? 'A'
        : 'B';
    if (current.phase == 'MATCH_OVER') {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            isTeam
                ? '$winningTeam 队赢得对局${winningTeam == ownTeam ? '，你和队友胜利！' : '。'}'
                : winner?.userId == widget.session.user?.id
                ? '你赢得了对局！'
                : '${winner == null ? '玩家' : _playerName(winner.userId)} 赢得了对局',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          Text(
            isTeam
                ? '${winner == null ? '一位队员' : _playerName(winner.userId)}先出完手牌 · 对手剩余手牌 ${current.roundPoints} 分'
                : '本轮得分 ${current.roundPoints} 分',
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: () => widget.onBackToRoom(true),
            child: const Text('返回等待室 · 再来一局'),
          ),
        ],
      );
    }
    if (current.phase == 'ROUND_OVER') {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            winner?.userId == widget.session.user?.id
                ? '你赢得了本轮！'
                : '${winner == null ? '玩家' : _playerName(winner.userId)} 赢得了本轮',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          Text('本轮得分 ${current.roundPoints} 分'),
          _action('开始下一轮', () => _send('NEXT_ROUND'), filled: true),
        ],
      );
    }
    if (current.phase == 'INITIAL_WILD_COLOR' && myTurn) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('选择开局颜色'),
          _colorChoices(submitImmediately: true),
        ],
      );
    }
    if (current.phase == 'DRAW_FOUR_RESPONSE' && myTurn) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('你收到了 +4，8 秒内可质疑。'),
          _action('接受 · 摸 4 张', () => _send('ACCEPT_DRAW_FOUR'), filled: true),
          _action('质疑 +4', () => _send('CHALLENGE_DRAW_FOUR')),
        ],
      );
    }
    final card = current.ownHand
        .where((item) => item.id == selectedCardId)
        .firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (activeTurn) ...[
          Text(card == null ? '从手牌中选择一张。' : '已选：${card.label}'),
          if (card?.color == null && card != null)
            _colorChoices(submitImmediately: false),
          if (current.ownHand.length == 2 && card != null)
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('出牌时喊 UNO'),
              value: callUno,
              onChanged: canSend
                  ? (value) => setState(() => callUno = value ?? false)
                  : null,
            ),
          FilledButton(
            onPressed:
                canSend &&
                    card != null &&
                    (card.color != null || chosenColor != null)
                ? _playSelected
                : null,
            child: const Text('打出选中的牌'),
          ),
          if (current.phase == 'TURN') _action('摸 1 张', () => _send('DRAW')),
          if (current.phase == 'AFTER_DRAW')
            _action('不出刚摸的牌 · 结束回合', () => _send('PASS')),
        ] else
          const Text('等待当前玩家完成操作。'),
        if (current.unoVulnerableSeat != null) ...[
          const Divider(),
          if (current.unoVulnerableSeat == ownSeat)
            _action('喊 UNO！', () => _send('SAY_UNO'))
          else
            _action('抓漏喊 UNO', () {
              final target = current.players[current.unoVulnerableSeat!];
              _send('CATCH_UNO', targetUserId: target.userId);
            }),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final current = view;
    final remaining = state?.deadlineAt?.difference(now).inSeconds;
    final secondsLeft = remaining?.clamp(0, 3600);
    final turn = current == null
        ? ''
        : current.phase == 'MATCH_OVER'
        ? '对局结束'
        : current.phase == 'ROUND_OVER'
        ? '本轮结束'
        : myTurn
        ? '轮到你'
        : '轮到 ${_playerName(current.players[current.currentSeat].userId)}';
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) widget.onBackToRoom(false);
      },
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            isTeam ? '双人组牌桌' : '经典牌桌',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          Text(
            isTeam
                ? '你在 ${ownTeam ?? '—'} 队 · 队友手牌不公开 · 服务器决定胜负'
                : '第 ${current?.roundNumber ?? '—'} 轮 · 服务器决定出牌与胜负',
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              Chip(
                label: Text(switch (status) {
                  MatchSocketStatus.connected => '实时连接',
                  MatchSocketStatus.connecting => '正在连接',
                  MatchSocketStatus.disconnected => '连接中断 · 正在重连',
                  MatchSocketStatus.unauthorized => '会话失效',
                  MatchSocketStatus.takenOver => '已由另一窗口接管',
                }),
              ),
              if (status == MatchSocketStatus.takenOver)
                OutlinedButton(onPressed: _open, child: const Text('在此接管')),
              if (current != null) Chip(label: Text(turn)),
              if (secondsLeft != null) Chip(label: Text('剩余 $secondsLeft 秒')),
              if (current != null)
                Chip(label: Text(current.direction == 1 ? '顺时针 ↻' : '逆时针 ↺')),
            ],
          ),
          if (error.isNotEmpty)
            Card(
              color: const Color(0xffffe4df),
              child: ListTile(
                title: Text(error),
                trailing: TextButton(onPressed: _sync, child: const Text('同步')),
              ),
            ),
          if (notice.isNotEmpty)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(notice),
              ),
            ),
          if (current == null) ...[
            const SizedBox(height: 30),
            const Text('正在读取牌局；如连接失败，请重试。'),
            OutlinedButton(onPressed: _open, child: const Text('重新读取')),
          ] else ...[
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xff355c48),
                borderRadius: BorderRadius.circular(20),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 8,
                    children: [
                      for (final player in current.players)
                        if (player.seat != ownSeat)
                          Chip(
                            label: Text(
                              '${_playerName(player.userId)}${isTeam ? ' · ${player.seat.isEven ? 'A' : 'B'} 队${player.seat.isEven == ownSeat.isEven ? '（队友）' : ''}' : ''} · ${player.handCount} 张 · ${player.score} 分',
                            ),
                          ),
                    ],
                  ),
                  const SizedBox(height: 28),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Image.asset('assets/cards/back.png', height: 95),
                      const SizedBox(width: 14),
                      Image.asset(current.topCard.asset, height: 95),
                      const SizedBox(width: 14),
                      Flexible(
                        child: Text(
                          '当前颜色：${_colorName(current.activeColor)}\n牌堆 ${current.drawCount} 张',
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 28),
                  Text(
                    '你的手牌 · ${current.ownHand.length} 张 · '
                    '${ownSeat < 0 ? 0 : current.players[ownSeat].score} 分',
                    style: const TextStyle(color: Colors.white),
                  ),
                  const SizedBox(height: 8),
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        for (final card in current.ownHand)
                          Padding(
                            padding: const EdgeInsets.only(right: 5),
                            child: Semantics(
                              label: '选择${card.label}',
                              button: true,
                              selected: selectedCardId == card.id,
                              child: GestureDetector(
                                key: ValueKey('card-${card.id}'),
                                onTap:
                                    !activeTurn ||
                                        !canSend ||
                                        (current.phase == 'AFTER_DRAW' &&
                                            current.drawnCardId != card.id)
                                    ? null
                                    : () => setState(() {
                                        selectedCardId =
                                            selectedCardId == card.id
                                            ? null
                                            : card.id;
                                        chosenColor = null;
                                      }),
                                child: DecoratedBox(
                                  decoration: BoxDecoration(
                                    border: Border.all(
                                      color: selectedCardId == card.id
                                          ? const Color(0xffffdf80)
                                          : Colors.transparent,
                                      width: 3,
                                    ),
                                    borderRadius: BorderRadius.circular(9),
                                  ),
                                  child: Image.asset(
                                    card.asset,
                                    width: 62,
                                    height: 92,
                                    fit: BoxFit.contain,
                                  ),
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '本回合操作',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    Text(pending == null ? '结果以服务器返回为准。' : '等待服务器确认…'),
                    const SizedBox(height: 10),
                    _controls(current),
                    if (evidence.isNotEmpty) ...[
                      const Divider(),
                      const Text('质疑时的证据手牌'),
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            for (final card in evidence)
                              Image.asset(card.asset, width: 45),
                          ],
                        ),
                      ),
                    ],
                    TextButton(onPressed: _sync, child: const Text('同步最新状态')),
                  ],
                ),
              ),
            ),
          ],
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _toggleAudioPreference,
            icon: Icon(muted ? Icons.volume_off : Icons.volume_up),
            label: Text(muted ? '开启音效' : '静音音效'),
          ),
          OutlinedButton(
            onPressed: () => widget.onBackToRoom(false),
            child: const Text('返回等待室'),
          ),
          RoomChat(
            roomId: widget.room.id,
            api: chatApi,
            teamEnabled: widget.room.mode == 'TEAM_2V2',
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    generation++;
    socket?.close();
    clock.cancel();
    chatApi.close();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}
