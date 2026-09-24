import 'dart:math';

class MatchDataFailure implements Exception {
  const MatchDataFailure();

  @override
  String toString() => '对局数据异常，请同步状态后重试。';
}

Map<String, dynamic> _object(Object? value) {
  if (value is Map<String, dynamic>) return value;
  throw const MatchDataFailure();
}

String _text(Object? value) {
  if (value is String && value.isNotEmpty) return value;
  throw const MatchDataFailure();
}

int _integer(Object? value) {
  if (value is int) return value;
  throw const MatchDataFailure();
}

DateTime? _deadline(Object? value) {
  if (value == null) return null;
  if (value is String) {
    final parsed = DateTime.tryParse(value);
    if (parsed != null) return parsed.toUtc();
  }
  throw const MatchDataFailure();
}

const cardColors = ['RED', 'YELLOW', 'GREEN', 'BLUE'];
const matchPhases = [
  'INITIAL_WILD_COLOR',
  'TURN',
  'AFTER_DRAW',
  'DRAW_FOUR_RESPONSE',
  'ROUND_OVER',
  'MATCH_OVER',
];

class MatchCard {
  const MatchCard(this.id, this.color, this.kind, this.number);

  final int id;
  final String? color;
  final String kind;
  final int number;

  factory MatchCard.parse(Object? value) {
    final data = _object(value);
    final id = _integer(data['id']);
    final color = data['color'];
    final kind = _text(data['kind']);
    final number = _integer(data['number']);
    if (id < 0 ||
        id > 107 ||
        (color != null && (color is! String || !cardColors.contains(color))) ||
        !const [
          'NUMBER',
          'SKIP',
          'REVERSE',
          'DRAW_TWO',
          'WILD',
          'WILD_DRAW_FOUR',
        ].contains(kind)) {
      throw const MatchDataFailure();
    }
    return MatchCard(id, color as String?, kind, number);
  }

  String get face => switch (kind) {
    'NUMBER' => '$number',
    'SKIP' => 'skip',
    'REVERSE' => 'reverse',
    'DRAW_TWO' => 'draw-two',
    'WILD' => 'wild',
    _ => 'wild-draw-four',
  };

  String get asset =>
      'assets/cards/${color == null ? '' : '${color!.toLowerCase()}-'}$face.png';

  String get label {
    final name = switch (color) {
      'RED' => '红色',
      'YELLOW' => '黄色',
      'GREEN' => '绿色',
      'BLUE' => '蓝色',
      _ => '万能',
    };
    final value = switch (kind) {
      'NUMBER' => '$number',
      'SKIP' => '跳过',
      'REVERSE' => '反转',
      'DRAW_TWO' => '+2',
      'WILD' => '选色',
      _ => '+4',
    };
    return '$name $value';
  }
}

class MatchPlayer {
  const MatchPlayer(this.userId, this.seat, this.handCount, this.score);
  final String userId;
  final int seat;
  final int handCount;
  final int score;

  factory MatchPlayer.parse(Object? value) {
    final data = _object(value);
    final userId = _text(data['userId']);
    final seat = _integer(data['seat']);
    final handCount = _integer(data['handCount']);
    final score = _integer(data['score']);
    if (seat < 0 || handCount < 0 || score < 0) throw const MatchDataFailure();
    return MatchPlayer(userId, seat, handCount, score);
  }
}

class MatchView {
  const MatchView({
    required this.version,
    required this.roundNumber,
    required this.phase,
    required this.currentSeat,
    required this.direction,
    required this.topCard,
    required this.activeColor,
    required this.drawCount,
    required this.discardCount,
    required this.ownHand,
    required this.players,
    required this.unoVulnerableSeat,
    required this.roundWinnerSeat,
    required this.roundPoints,
    required this.canRespondToDrawFour,
    required this.drawnCardId,
  });

  final int version;
  final int roundNumber;
  final String phase;
  final int currentSeat;
  final int direction;
  final MatchCard topCard;
  final String? activeColor;
  final int drawCount;
  final int discardCount;
  final List<MatchCard> ownHand;
  final List<MatchPlayer> players;
  final int? unoVulnerableSeat;
  final int? roundWinnerSeat;
  final int roundPoints;
  final bool canRespondToDrawFour;
  final int? drawnCardId;

  factory MatchView.parse(Object? value) {
    final data = _object(value);
    final phase = _text(data['phase']);
    final hand = data['ownHand'];
    final rawPlayers = data['players'];
    final color = data['activeColor'];
    final vulnerable = data['unoVulnerableSeat'];
    final winner = data['roundWinnerSeat'];
    final drawn = data['drawnCardId'];
    final canRespond = data['canRespondToDrawFour'];
    if (_integer(data['rulesVersion']) != 1 ||
        !matchPhases.contains(phase) ||
        hand is! List ||
        rawPlayers is! List ||
        (color != null && (color is! String || !cardColors.contains(color))) ||
        (vulnerable != null && vulnerable is! int) ||
        (winner != null && winner is! int) ||
        (drawn != null && drawn is! int) ||
        canRespond is! bool) {
      throw const MatchDataFailure();
    }
    final players = rawPlayers.map(MatchPlayer.parse).toList(growable: false);
    final seat = _integer(data['currentSeat']);
    final version = _integer(data['version']);
    if (version < 1 ||
        _integer(data['roundNumber']) < 1 ||
        seat < 0 ||
        seat >= players.length ||
        players.length < 2 ||
        players.length > 6 ||
        players.asMap().entries.any((entry) => entry.value.seat != entry.key) ||
        (vulnerable != null &&
            (vulnerable < 0 || vulnerable >= players.length)) ||
        (winner != null && (winner < 0 || winner >= players.length)) ||
        (drawn != null && (drawn < 0 || drawn > 107)) ||
        !const [-1, 1].contains(_integer(data['direction'])) ||
        _integer(data['drawCount']) < 0 ||
        _integer(data['discardCount']) < 0 ||
        _integer(data['roundPoints']) < 0) {
      throw const MatchDataFailure();
    }
    return MatchView(
      version: version,
      roundNumber: _integer(data['roundNumber']),
      phase: phase,
      currentSeat: seat,
      direction: _integer(data['direction']),
      topCard: MatchCard.parse(data['topCard']),
      activeColor: color as String?,
      drawCount: _integer(data['drawCount']),
      discardCount: _integer(data['discardCount']),
      ownHand: hand.map(MatchCard.parse).toList(growable: false),
      players: players,
      unoVulnerableSeat: vulnerable as int?,
      roundWinnerSeat: winner as int?,
      roundPoints: _integer(data['roundPoints']),
      canRespondToDrawFour: canRespond,
      drawnCardId: drawn as int?,
    );
  }
}

class MatchState {
  const MatchState(
    this.view,
    this.deadlineAt,
    this.status,
    this.interruptionReason,
  );
  final MatchView view;
  final DateTime? deadlineAt;
  final String status;
  final String? interruptionReason;

  factory MatchState.parse(Object? value) {
    final data = _object(value);
    if (!data.containsKey('deadlineAt')) throw const MatchDataFailure();
    final status = _text(data['status']);
    if (!const ['PLAYING', 'ENDED', 'INTERRUPTED'].contains(status)) {
      throw const MatchDataFailure();
    }
    final interruptionReason = data['interruptionReason'];
    if (interruptionReason != null &&
        !const [
          'PLAYER_LEFT',
          'REPEATED_TURN_TIMEOUT',
        ].contains(interruptionReason)) {
      throw const MatchDataFailure();
    }
    return MatchState(
      MatchView.parse(data['view']),
      _deadline(data['deadlineAt']),
      status,
      interruptionReason as String?,
    );
  }
}

class MatchStart {
  const MatchStart(this.matchId, this.roomVersion, this.state);
  final String matchId;
  final int roomVersion;
  final MatchState state;

  factory MatchStart.parse(Object? value) {
    final data = _object(value);
    return MatchStart(
      _text(data['matchId']),
      _integer(data['roomVersion']),
      MatchState.parse(data),
    );
  }
}

class MatchReceipt {
  const MatchReceipt(
    this.commandId,
    this.duplicate,
    this.appliedVersion,
    this.event,
    this.state,
    this.privateChallengeEvidence,
  );
  final String commandId;
  final bool duplicate;
  final int appliedVersion;
  final String event;
  final MatchState state;
  final List<MatchCard> privateChallengeEvidence;

  factory MatchReceipt.parse(Object? value) {
    final data = _object(value);
    final duplicate = data['duplicate'];
    final evidence = data['privateChallengeEvidence'];
    if (duplicate is! bool || evidence is! List) throw const MatchDataFailure();
    return MatchReceipt(
      _text(data['commandId']),
      duplicate,
      _integer(data['appliedVersion']),
      _text(data['event']),
      MatchState.parse(data),
      evidence.map(MatchCard.parse).toList(growable: false),
    );
  }
}

class MatchCommand {
  MatchCommand(
    this.type,
    this.expectedVersion, {
    this.cardId,
    this.chosenColor,
    this.targetUserId,
    this.callUno,
    String? commandId,
  }) : commandId = commandId ?? _newCommandId();

  final String commandId;
  final String type;
  final int expectedVersion;
  final int? cardId;
  final String? chosenColor;
  final String? targetUserId;
  final bool? callUno;

  Map<String, Object?> toJson() => {
    'protocolVersion': 1,
    'commandId': commandId,
    'expectedVersion': expectedVersion,
    'type': type,
    if (cardId != null) 'cardId': cardId,
    if (chosenColor != null) 'chosenColor': chosenColor,
    if (targetUserId != null) 'targetUserId': targetUserId,
    if (callUno != null) 'callUno': callUno,
  };
}

String _newCommandId() {
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
