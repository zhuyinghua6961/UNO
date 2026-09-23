ALTER TABLE game.matches ADD COLUMN deadline_at TIMESTAMPTZ;

UPDATE game.matches
SET deadline_at = now() + CASE WHEN snapshot->>'phase' = 'DRAW_FOUR_RESPONSE'
    THEN interval '8 seconds' ELSE interval '30 seconds' END
WHERE state = 'PLAYING' AND snapshot->>'phase' NOT IN ('ROUND_OVER', 'MATCH_OVER');

CREATE INDEX matches_due_idx ON game.matches(deadline_at)
    WHERE state = 'PLAYING' AND deadline_at IS NOT NULL;

ALTER TABLE game.match_commands ADD COLUMN source VARCHAR(12) NOT NULL DEFAULT 'PLAYER'
    CHECK (source IN ('PLAYER', 'TIMEOUT'));
