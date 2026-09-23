CREATE TABLE game.matches (
    id UUID PRIMARY KEY,
    room_id UUID REFERENCES game.rooms(id) ON DELETE SET NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode = 'CLASSIC'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PLAYING', 'ENDED', 'INTERRUPTED')),
    rules_version SMALLINT NOT NULL CHECK (rules_version > 0),
    version BIGINT NOT NULL CHECK (version > 0),
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX one_playing_match_per_room ON game.matches(room_id) WHERE state = 'PLAYING';
CREATE INDEX matches_room_created_idx ON game.matches(room_id, created_at DESC);

CREATE TABLE game.match_players (
    match_id UUID NOT NULL REFERENCES game.matches(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    seat SMALLINT NOT NULL CHECK (seat BETWEEN 0 AND 5),
    PRIMARY KEY (match_id, user_id),
    UNIQUE (match_id, seat)
);

CREATE TABLE game.match_commands (
    match_id UUID NOT NULL REFERENCES game.matches(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL,
    command_id UUID NOT NULL,
    request_payload TEXT NOT NULL,
    applied_version BIGINT NOT NULL CHECK (applied_version > 0),
    event VARCHAR(40) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    cards_drawn JSONB NOT NULL,
    private_evidence JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, actor_user_id, command_id),
    FOREIGN KEY (match_id, actor_user_id) REFERENCES game.match_players(match_id, user_id)
);
