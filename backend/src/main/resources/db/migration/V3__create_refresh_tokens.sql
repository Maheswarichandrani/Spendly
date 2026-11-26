-- V3: refresh tokens.
-- One row per refresh token ever issued. The raw token is only ever in the app's secure
-- storage; we keep its SHA-256 hash, so a database leak gives nobody a working token.
--
-- family_id: every token that came from one login shares it. Rotation creates a new row
-- in the same family. If an already-used token shows up again, the whole family (that one
-- device's login) is revoked.

CREATE TABLE refresh_tokens (
    id            UUID        PRIMARY KEY,
    -- ON DELETE CASCADE: deleting a user removes their sessions with them.
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id     UUID        NOT NULL,
    token_hash    TEXT        NOT NULL,
    -- Free text from the app, e.g. "Pixel 8". Shown later in "your devices".
    device_label  TEXT,
    expires_at    TIMESTAMPTZ NOT NULL,
    -- NULL = still usable. Set on rotation, logout, logout-all, or reuse detection.
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,

    -- Every refresh looks a token up by its hash. The unique index serves that lookup.
    CONSTRAINT refresh_tokens_token_hash_key       UNIQUE (token_hash),
    -- Lowercase hex SHA-256. Stops a bug from storing the raw token by mistake.
    CONSTRAINT refresh_tokens_hash_format          CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_tokens_device_label_length  CHECK (length(device_label) <= 100)
);

-- logout-all ("revoke every token of this user") and the FK cascade on user delete.
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
-- Reuse detection and logout revoke a whole family at once.
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
