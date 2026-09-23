ALTER TABLE accounts ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN absolute_expires_at TIMESTAMPTZ;
UPDATE sessions SET absolute_expires_at = expires_at;
ALTER TABLE sessions ALTER COLUMN absolute_expires_at SET NOT NULL;
ALTER TABLE sessions ALTER COLUMN absolute_expires_at SET DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days');
ALTER TABLE sessions ADD CONSTRAINT sessions_absolute_expiry_valid CHECK (absolute_expires_at >= expires_at);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    token_digest VARCHAR(64) NOT NULL UNIQUE CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    consumed_at TIMESTAMPTZ CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX refresh_tokens_session_idx ON refresh_tokens(session_id);
CREATE INDEX refresh_tokens_expiry_idx ON refresh_tokens(expires_at);

CREATE TABLE auth_rate_limits (
    bucket_key VARCHAR(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
CREATE INDEX auth_rate_limits_window_idx ON auth_rate_limits(window_started_at);

CREATE TABLE mail_outbox (
    id UUID PRIMARY KEY,
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    encrypted_body TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(32),
    CHECK (sent_at IS NOT NULL OR encrypted_body IS NOT NULL)
);
CREATE INDEX mail_outbox_pending_idx ON mail_outbox(next_attempt_at) WHERE sent_at IS NULL;
