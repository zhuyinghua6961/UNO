ALTER TABLE game.match_players ADD COLUMN consecutive_timeouts SMALLINT NOT NULL DEFAULT 0
    CHECK (consecutive_timeouts BETWEEN 0 AND 3);

ALTER TABLE game.matches ADD COLUMN interruption_reason VARCHAR(32);

-- Older classic rounds had no deadline and could wait forever for a manual next round.
UPDATE game.matches SET deadline_at = now() + INTERVAL '120 seconds'
WHERE state = 'PLAYING' AND snapshot->>'phase' = 'ROUND_OVER' AND deadline_at IS NULL;
