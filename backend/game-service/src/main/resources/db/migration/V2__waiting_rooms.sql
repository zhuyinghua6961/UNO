CREATE TABLE game.rooms (
    id UUID PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('CLASSIC', 'TEAM_2V2')),
    max_players SMALLINT NOT NULL CHECK (max_players BETWEEN 2 AND 6),
    host_user_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING', 'STARTING', 'PLAYING')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (mode <> 'TEAM_2V2' OR max_players = 4)
);

CREATE TABLE game.room_members (
    room_id UUID NOT NULL REFERENCES game.rooms(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    nickname VARCHAR(80) NOT NULL,
    seat SMALLINT NOT NULL CHECK (seat BETWEEN 0 AND 5),
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id),
    UNIQUE (room_id, seat),
    UNIQUE (user_id)
);

CREATE INDEX room_expiry_idx ON game.rooms(expires_at);
CREATE INDEX room_members_join_order_idx ON game.room_members(room_id, joined_at, user_id);
