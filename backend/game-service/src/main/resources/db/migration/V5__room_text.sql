CREATE TABLE game.chat_channel_sequences (
    room_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('ROOM', 'TEAM_A', 'TEAM_B')),
    last_sequence BIGINT NOT NULL CHECK (last_sequence > 0),
    PRIMARY KEY (room_id, channel)
);

CREATE TABLE game.chat_messages (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('ROOM', 'TEAM_A', 'TEAM_B')),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    sender_user_id UUID NOT NULL,
    sender_nickname VARCHAR(80) NOT NULL,
    client_message_id UUID NOT NULL,
    content TEXT NOT NULL CHECK (char_length(content) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (room_id, channel, sequence),
    UNIQUE (room_id, sender_user_id, client_message_id)
);

CREATE INDEX chat_messages_sender_rate_idx ON game.chat_messages(sender_user_id, created_at DESC);
CREATE INDEX chat_messages_expiry_idx ON game.chat_messages(expires_at);
