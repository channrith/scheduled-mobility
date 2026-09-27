-- identity module: users, role grants and refresh tokens.

CREATE TABLE identity.users (
    id              UUID         PRIMARY KEY,
    phone_e164      VARCHAR(16)  NOT NULL UNIQUE CHECK (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    telegram_id     BIGINT       UNIQUE,
    full_name       VARCHAR(200),
    preferred_lang  VARCHAR(5)   NOT NULL DEFAULT 'km' CHECK (preferred_lang IN ('km', 'en')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE identity.user_roles (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    role        VARCHAR(32)  NOT NULL CHECK (role IN ('PASSENGER', 'DRIVER', 'CORPORATE_ADMIN', 'CORPORATE_BOOKER',
                                                      'DISPATCHER', 'SUPPORT', 'SAFETY_OFFICER', 'ADMIN')),
    -- corporate_id for CORPORATE_* roles; NULL for global roles.
    scope_id    UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_roles UNIQUE NULLS NOT DISTINCT (user_id, role, scope_id),
    CONSTRAINT ck_user_roles_corporate_scope CHECK (
        (role IN ('CORPORATE_ADMIN', 'CORPORATE_BOOKER')) = (scope_id IS NOT NULL)
    )
);

CREATE INDEX ix_user_roles_user_id ON identity.user_roles (user_id);

-- Opaque refresh tokens, stored as SHA-256 hashes. A family is one login session;
-- each rotation revokes the current token and links it to its replacement.
CREATE TABLE identity.refresh_tokens (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    family_id    UUID         NOT NULL,
    token_hash   BYTEA        NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID         REFERENCES identity.refresh_tokens (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_refresh_tokens_family_id ON identity.refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user_id ON identity.refresh_tokens (user_id);
