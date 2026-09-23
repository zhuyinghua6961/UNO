ALTER TABLE game.room_members ADD COLUMN team_join_sequence BIGINT NOT NULL DEFAULT 0
    CHECK (team_join_sequence >= 0);

UPDATE game.room_members member SET team_join_sequence = COALESCE((
    SELECT sequence.last_sequence FROM game.chat_channel_sequences sequence
    JOIN game.rooms room ON room.id = member.room_id
    WHERE sequence.room_id = member.room_id
      AND sequence.channel = CASE WHEN member.seat % 2 = 0 THEN 'TEAM_A' ELSE 'TEAM_B' END
      AND room.mode = 'TEAM_2V2'
), 0);
