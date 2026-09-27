CREATE TABLE game.match_socket_ownership (
    match_id UUID NOT NULL,
    user_id UUID NOT NULL,
    owner_token UUID NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, user_id),
    FOREIGN KEY (match_id, user_id) REFERENCES game.match_players(match_id, user_id) ON DELETE CASCADE
);
