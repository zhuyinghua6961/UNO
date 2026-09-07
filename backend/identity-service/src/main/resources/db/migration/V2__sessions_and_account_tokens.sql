CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    client_type VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT sessions_digest_valid CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT sessions_client_valid CHECK (client_type IN ('WEB', 'APP')),
    CONSTRAINT sessions_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT sessions_revocation_valid CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX sessions_account_id_idx ON sessions(account_id);
CREATE INDEX sessions_expires_at_idx ON sessions(expires_at);

CREATE TABLE account_tokens (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose VARCHAR(16) NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT account_tokens_purpose_valid CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    CONSTRAINT account_tokens_digest_valid CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT account_tokens_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT account_tokens_consumption_valid CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX account_tokens_account_id_idx ON account_tokens(account_id);
CREATE INDEX account_tokens_expires_at_idx ON account_tokens(expires_at);
