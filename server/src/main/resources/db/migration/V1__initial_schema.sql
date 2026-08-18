CREATE TABLE users (
    id uuid PRIMARY KEY,
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'PENDING_DELETE', 'DISABLED')),
    display_name varchar(40),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    delete_after timestamptz
);

CREATE TABLE auth_identities (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider varchar(24) NOT NULL,
    identifier_hmac char(64) NOT NULL,
    identifier_ciphertext text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (provider, identifier_hmac)
);

CREATE TABLE consent_acceptances (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    terms_version varchar(40) NOT NULL,
    privacy_version varchar(40) NOT NULL,
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, terms_version, privacy_version)
);

CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id uuid NOT NULL,
    device_name varchar(80),
    platform varchar(20) NOT NULL,
    app_version varchar(30) NOT NULL,
    access_token_hash char(64) NOT NULL UNIQUE,
    access_expires_at timestamptz NOT NULL,
    refresh_token_hash char(64) NOT NULL UNIQUE,
    refresh_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    revoked_at timestamptz,
    UNIQUE (user_id, device_id)
);
CREATE INDEX sessions_active_access_idx ON sessions(access_token_hash) WHERE revoked_at IS NULL;
CREATE INDEX sessions_active_refresh_idx ON sessions(refresh_token_hash) WHERE revoked_at IS NULL;

CREATE TABLE sms_challenges (
    id uuid PRIMARY KEY,
    purpose varchar(24) NOT NULL,
    phone_hmac char(64) NOT NULL,
    phone_ciphertext text NOT NULL,
    code_hash char(64) NOT NULL,
    client_request_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    resend_after timestamptz NOT NULL,
    attempts smallint NOT NULL DEFAULT 0,
    consumed_at timestamptz
);
CREATE INDEX sms_challenges_phone_created_idx ON sms_challenges(phone_hmac, created_at DESC);

CREATE SEQUENCE sync_change_seq START WITH 1;

CREATE TABLE sync_entities (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    id uuid NOT NULL,
    entity_type varchar(24) NOT NULL CHECK (entity_type IN ('FAVORITE', 'NOTE', 'CUSTOM_ITEM', 'JOURNAL')),
    subject_id varchar(160),
    city_code varchar(20),
    category smallint,
    payload jsonb,
    version bigint NOT NULL CHECK (version > 0),
    change_seq bigint NOT NULL,
    client_updated_at timestamptz NOT NULL,
    server_updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    PRIMARY KEY (user_id, id)
);
CREATE INDEX sync_entities_changes_idx ON sync_entities(user_id, change_seq);

CREATE TABLE sync_mutations (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mutation_id uuid NOT NULL,
    entity_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, mutation_id)
);
CREATE INDEX sync_mutations_created_idx ON sync_mutations(created_at);
