ALTER TABLE game.match_players ADD COLUMN nickname_snapshot VARCHAR(80);

UPDATE game.match_players players SET nickname_snapshot = members.nickname
FROM game.matches matches, game.room_members members
WHERE matches.id = players.match_id AND members.room_id = matches.room_id
  AND members.user_id = players.user_id;

CREATE INDEX matches_finished_history_idx ON game.matches(ended_at DESC, id DESC)
WHERE state = 'ENDED';
