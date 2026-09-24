CREATE TABLE game.chat_reports (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    message_id UUID NOT NULL,
    reporter_user_id UUID NOT NULL,
    reported_user_id UUID NOT NULL,
    reason VARCHAR(16) NOT NULL CHECK (reason IN ('SPAM', 'ABUSE', 'OTHER')),
    content_snapshot TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    UNIQUE (message_id, reporter_user_id)
);

CREATE INDEX chat_reports_review_idx ON game.chat_reports(status, created_at, id);
CREATE INDEX chat_reports_expiry_idx ON game.chat_reports(expires_at);

CREATE TABLE game.chat_mutes (
    user_id UUID PRIMARY KEY,
    muted_until TIMESTAMPTZ NOT NULL,
    reason VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX chat_mutes_retention_idx ON game.chat_mutes(muted_until);
