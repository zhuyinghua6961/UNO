import 'match_models.dart';

class MatchHistoryPage {
  const MatchHistoryPage(this.items, this.nextCursor);
  final List<MatchHistoryItem> items;
  final String? nextCursor;

  factory MatchHistoryPage.parse(Object? value) {
    if (value is! Map<String, dynamic> || value['items'] is! List ||
        (value['nextCursor'] != null && value['nextCursor'] is! String)) {
      throw const MatchDataFailure();
    }
    return MatchHistoryPage(
      (value['items'] as List).map(MatchHistoryItem.parse).toList(growable: false),
      value['nextCursor'] as String?,
    );
  }
}

class MatchHistoryItem {
  const MatchHistoryItem(this.matchId, this.endedAt, this.rounds, this.result, this.players);
  final String matchId;
  final DateTime endedAt;
  final int rounds;
  final String result;
  final List<MatchHistoryPlayer> players;

  factory MatchHistoryItem.parse(Object? value) {
    if (value is! Map<String, dynamic> || value['matchId'] is! String ||
        value['mode'] != 'CLASSIC' || value['endedAt'] is! String ||
        value['rounds'] is! int || value['rounds'] < 1 ||
        !const ['WIN', 'LOSS'].contains(value['result']) ||
        value['players'] is! List) {
      throw const MatchDataFailure();
    }
    final ended = DateTime.tryParse(value['endedAt'] as String);
    if (ended == null) throw const MatchDataFailure();
    return MatchHistoryItem(
      value['matchId'] as String,
      ended.toLocal(),
      value['rounds'] as int,
      value['result'] as String,
      (value['players'] as List).map(MatchHistoryPlayer.parse).toList(growable: false),
    );
  }
}

class MatchHistoryPlayer {
  const MatchHistoryPlayer(this.userId, this.nickname, this.score);
  final String userId;
  final String? nickname;
  final int score;

  factory MatchHistoryPlayer.parse(Object? value) {
    if (value is! Map<String, dynamic> || value['userId'] is! String ||
        (value['nickname'] != null && value['nickname'] is! String) ||
        value['seat'] is! int || value['score'] is! int) {
      throw const MatchDataFailure();
    }
    return MatchHistoryPlayer(value['userId'] as String, value['nickname'] as String?,
        value['score'] as int);
  }
}
