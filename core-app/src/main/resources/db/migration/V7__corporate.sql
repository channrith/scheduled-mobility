-- corporate module: client accounts, cost centres and members with a booking policy.

CREATE TABLE corporate.corporate_accounts (
    id                  UUID          PRIMARY KEY,
    legal_name          VARCHAR(200)  NOT NULL,
    display_name        VARCHAR(100),
    -- Company tax identification number (TIN), normalized (uppercase, no spaces).
    tax_id              VARCHAR(30)   UNIQUE,
    payment_terms_days  INTEGER       NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 120),
    credit_limit_minor  BIGINT        NOT NULL DEFAULT 0 CHECK (credit_limit_minor >= 0),
    currency            CHAR(3)       NOT NULL DEFAULT 'USD' CHECK (currency IN ('USD', 'KHR')),
    service_fee_pct     NUMERIC(5, 2) NOT NULL DEFAULT 8.00 CHECK (service_fee_pct BETWEEN 0 AND 100),
    status              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    suspension_reason   TEXT,
    suspended_at        TIMESTAMPTZ,
    suspended_by        UUID,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_corporate_suspension CHECK (status <> 'SUSPENDED'
        OR (suspension_reason IS NOT NULL AND suspended_at IS NOT NULL))
);

CREATE INDEX ix_corporate_accounts_name ON corporate.corporate_accounts USING gin (lower(legal_name) gin_trgm_ops);

-- Suspend/reactivate reasons (staff text stays here, not in the audit log).
CREATE TABLE corporate.account_status_history (
    id             UUID         PRIMARY KEY,
    account_id     UUID         NOT NULL REFERENCES corporate.corporate_accounts (id),
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    reason         TEXT,
    actor_user_id  UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_account_status_history ON corporate.account_status_history (account_id, occurred_at);

CREATE TABLE corporate.cost_centers (
    id          UUID          PRIMARY KEY,
    account_id  UUID          NOT NULL REFERENCES corporate.corporate_accounts (id),
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_cost_centers_code UNIQUE (account_id, code),
    -- Target of the composite foreign key below.
    CONSTRAINT uq_cost_centers_account_id UNIQUE (account_id, id)
);

CREATE TABLE corporate.corporate_members (
    id              UUID          PRIMARY KEY,
    account_id      UUID          NOT NULL REFERENCES corporate.corporate_accounts (id),
    -- identity.users by value only: modules never share foreign keys.
    user_id         UUID          NOT NULL,
    role            VARCHAR(20)   NOT NULL CHECK (role IN ('CORPORATE_ADMIN', 'CORPORATE_BOOKER', 'RIDER')),
    cost_center_id  UUID,
    employee_ref    VARCHAR(50),
    -- Booking policy; a missing field means "no restriction". Validated by the application.
    policy          JSONB         NOT NULL DEFAULT '{}',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REMOVED')),
    removed_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_members_account_user UNIQUE (account_id, user_id),
    -- A member's cost centre always belongs to the member's own company.
    CONSTRAINT fk_members_cost_center FOREIGN KEY (account_id, cost_center_id)
        REFERENCES corporate.cost_centers (account_id, id)
);

CREATE INDEX ix_corporate_members_user ON corporate.corporate_members (user_id);
