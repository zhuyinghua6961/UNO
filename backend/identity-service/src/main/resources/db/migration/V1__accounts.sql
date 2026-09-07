CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounts_email_unique UNIQUE (email),
    CONSTRAINT accounts_email_normalized CHECK (email = lower(btrim(email)) AND email LIKE '%_@_%'),
    CONSTRAINT accounts_password_hash_present CHECK (length(btrim(password_hash)) > 0),
    CONSTRAINT accounts_nickname_present CHECK (length(btrim(nickname)) > 0),
    CONSTRAINT accounts_status_valid CHECK (status IN ('PENDING', 'ACTIVE', 'DISABLED')),
    CONSTRAINT accounts_timestamps_valid CHECK (updated_at >= created_at)
);
