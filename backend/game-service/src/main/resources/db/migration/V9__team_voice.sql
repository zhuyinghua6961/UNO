ALTER TABLE game.matches ADD COLUMN voice_generation UUID NOT NULL DEFAULT gen_random_uuid();

CREATE TABLE game.voice_token_issuance (
    match_id UUID NOT NULL REFERENCES game.matches(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (match_id, user_id),
    FOREIGN KEY (match_id, user_id) REFERENCES game.match_players(match_id, user_id)
);

CREATE TABLE game.voice_cleanup (
    match_id UUID PRIMARY KEY REFERENCES game.matches(id) ON DELETE CASCADE,
    voice_generation UUID NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INT NOT NULL DEFAULT 0
);
