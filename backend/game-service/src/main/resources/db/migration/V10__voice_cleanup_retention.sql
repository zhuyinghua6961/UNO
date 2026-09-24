ALTER TABLE game.voice_cleanup
    ADD COLUMN retain_until TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '70 seconds');
