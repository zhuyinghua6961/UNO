import 'match_models.dart';

class MatchModeStats {
  const MatchModeStats(this.wins, this.losses, this.interrupted);
  final int wins;
  final int losses;
  final int interrupted;

  factory MatchModeStats.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['wins'] is! int ||
        value['wins'] < 0 ||
        value['losses'] is! int ||
        value['losses'] < 0 ||
        value['interrupted'] is! int ||
        value['interrupted'] < 0) {
      throw const MatchDataFailure();
    }
    return MatchModeStats(
      value['wins'] as int,
      value['losses'] as int,
      value['interrupted'] as int,
    );
  }

  String get summary {
    final completed = wins + losses;
    final rate = completed == 0 ? '暂无' : '${(wins * 100 / completed).round()}%';
    return '$wins 胜 · $losses 负 · 完赛胜率 $rate · $interrupted 场中断';
  }
}

class MatchStats {
  const MatchStats(this.classic, this.team2v2);
  final MatchModeStats classic;
  final MatchModeStats team2v2;

  factory MatchStats.parse(Object? value) {
    if (value is! Map<String, dynamic>) throw const MatchDataFailure();
    return MatchStats(
      MatchModeStats.parse(value['classic']),
      MatchModeStats.parse(value['team2v2']),
    );
  }
}

class MatchHistoryPage {
  const MatchHistoryPage(this.items, this.nextCursor);
  final List<MatchHistoryItem> items;
  final String? nextCursor;

  factory MatchHistoryPage.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['items'] is! List ||
        (value['nextCursor'] != null && value['nextCursor'] is! String)) {
      throw const MatchDataFailure();
    }
    return MatchHistoryPage(
      (value['items'] as List)
          .map(MatchHistoryItem.parse)
          .toList(growable: false),
      value['nextCursor'] as String?,
    );
  }
}

class MatchHistoryItem {
  const MatchHistoryItem(
    this.matchId,
    this.mode,
    this.endedAt,
    this.rounds,
    this.result,
    this.players,
  );
  final String matchId;
  final String mode;
  final DateTime endedAt;
  final int rounds;
  final String result;
  final List<MatchHistoryPlayer> players;

  factory MatchHistoryItem.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['matchId'] is! String ||
        !const ['CLASSIC', 'TEAM_2V2'].contains(value['mode']) ||
        value['endedAt'] is! String ||
        value['rounds'] is! int ||
        value['rounds'] < 1 ||
        !const ['WIN', 'LOSS', 'INTERRUPTED'].contains(value['result']) ||
        value['players'] is! List) {
      throw const MatchDataFailure();
    }
    final ended = DateTime.tryParse(value['endedAt'] as String);
    if (ended == null) throw const MatchDataFailure();
    return MatchHistoryItem(
      value['matchId'] as String,
      value['mode'] as String,
      ended.toLocal(),
      value['rounds'] as int,
      value['result'] as String,
      (value['players'] as List)
          .map(MatchHistoryPlayer.parse)
          .toList(growable: false),
    );
  }
}

class MatchHistoryPlayer {
  const MatchHistoryPlayer(this.userId, this.nickname, this.score);
  final String userId;
  final String? nickname;
  final int score;

  factory MatchHistoryPlayer.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['userId'] is! String ||
        (value['nickname'] != null && value['nickname'] is! String) ||
        value['seat'] is! int ||
        value['score'] is! int) {
      throw const MatchDataFailure();
    }
    return MatchHistoryPlayer(
      value['userId'] as String,
      value['nickname'] as String?,
      value['score'] as int,
    );
  }
}
