const matchId = '9c766d3d-7918-46e2-a96e-5967b70c13aa';
const roomId = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86';
const userId = 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc';
const guestId = '1ce577e1-a64c-48ca-8412-720d08ad408c';

Map<String, Object?> matchSnapshot({
  int version = 4,
  String phase = 'TURN',
  int currentSeat = 0,
}) => {
  'deadlineAt': '2026-09-23T10:00:00Z',
  'view': {
    'rulesVersion': 1,
    'version': version,
    'roundNumber': 1,
    'phase': phase,
    'currentSeat': currentSeat,
    'direction': 1,
    'topCard': {'id': 1, 'color': 'RED', 'kind': 'NUMBER', 'number': 1},
    'activeColor': 'RED',
    'drawCount': 92,
    'discardCount': 2,
    'ownHand': [
      {'id': 2, 'color': 'RED', 'kind': 'NUMBER', 'number': 1},
      {'id': 104, 'color': null, 'kind': 'WILD', 'number': -1},
    ],
    'players': [
      {'userId': userId, 'seat': 0, 'handCount': 2, 'score': 0},
      {'userId': guestId, 'seat': 1, 'handCount': 7, 'score': 0},
    ],
    'unoVulnerableSeat': null,
    'roundWinnerSeat': null,
    'roundPoints': 0,
    'canRespondToDrawFour': false,
    'drawnCardId': null,
  },
};
