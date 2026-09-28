CREATE TABLE game.voice_issued_sessions (
    match_id UUID NOT NULL REFERENCES game.matches(id) ON DELETE CASCADE,
    voice_generation UUID NOT NULL,
    session_id UUID NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (match_id, voice_generation, session_id)
);

-- Older grants have no session ledger. Move live matches to fresh rooms so those
-- grants cannot rejoin a teammate after this migration.
INSERT INTO game.voice_cleanup(match_id, voice_generation, next_attempt_at, retain_until)
SELECT m.id, m.voice_generation, now(), now() + INTERVAL '70 seconds'
FROM game.matches m
WHERE m.mode = 'TEAM_2V2' AND m.state = 'PLAYING'
  AND EXISTS (SELECT 1 FROM game.voice_token_issuance i WHERE i.match_id = m.id)
ON CONFLICT (match_id, voice_generation) DO NOTHING;

UPDATE game.matches m
SET voice_generation = gen_random_uuid(), voice_reviewed_at = NULL
WHERE m.mode = 'TEAM_2V2' AND m.state = 'PLAYING'
  AND EXISTS (SELECT 1 FROM game.voice_token_issuance i WHERE i.match_id = m.id);
