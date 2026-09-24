ALTER TABLE game.voice_cleanup DROP CONSTRAINT voice_cleanup_pkey;
ALTER TABLE game.voice_cleanup ADD PRIMARY KEY (match_id, voice_generation);

ALTER TABLE game.matches ADD COLUMN voice_reviewed_at TIMESTAMPTZ;
CREATE INDEX matches_voice_review_idx ON game.matches(voice_reviewed_at)
    WHERE mode = 'TEAM_2V2' AND state = 'PLAYING';
