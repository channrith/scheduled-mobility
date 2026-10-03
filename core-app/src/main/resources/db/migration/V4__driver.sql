-- driver module. user_id references identity.users by value only: modules never share foreign keys.

CREATE TABLE driver.drivers (
    id                    UUID          PRIMARY KEY,
    user_id               UUID          NOT NULL UNIQUE,
    full_name             VARCHAR(200)  NOT NULL,
    phone_e164            VARCHAR(16)   NOT NULL,
    status                VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'DOCS_SUBMITTED', 'TRAINING',
                                                                   'APPROVED', 'REJECTED', 'SUSPENDED')),
    -- Personal data, AES-GCM encrypted by the application. national_id_hash is an HMAC blind index.
    national_id_enc       BYTEA,
    national_id_hash      BYTEA         UNIQUE,
    bank_name             VARCHAR(100),
    bank_account_name     VARCHAR(200),
    bank_account_enc      BYTEA,
    suspension_reason     TEXT,
    suspension_notice_at  TIMESTAMPTZ,
    suspended_at          TIMESTAMPTZ,
    suspended_by          UUID,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0,
    -- Due process: a suspension always has a reason and the time the driver was notified.
    CONSTRAINT ck_drivers_suspension CHECK (status <> 'SUSPENDED' OR (suspension_reason IS NOT NULL
        AND suspension_notice_at IS NOT NULL AND suspended_at IS NOT NULL)),
    CONSTRAINT ck_drivers_national_id CHECK ((national_id_enc IS NULL) = (national_id_hash IS NULL))
);

CREATE INDEX ix_drivers_status ON driver.drivers (status, created_at);

-- One row per status transition.
CREATE TABLE driver.driver_status_history (
    id             UUID         PRIMARY KEY,
    driver_id      UUID         NOT NULL REFERENCES driver.drivers (id),
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    actor_user_id  UUID,
    reason         TEXT,
    notice_at      TIMESTAMPTZ,
    occurred_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_driver_status_history_driver ON driver.driver_status_history (driver_id, occurred_at);

CREATE TABLE driver.vehicles (
    id             UUID         PRIMARY KEY,
    plate_number   VARCHAR(20)  NOT NULL UNIQUE,
    vehicle_class  VARCHAR(20)  NOT NULL CHECK (vehicle_class IN ('MOTO', 'TUKTUK_REMORK', 'TUKTUK_RICKSHAW',
                                                                  'CAR', 'SUV', 'VAN')),
    make           VARCHAR(50),
    model          VARCHAR(50),
    color          VARCHAR(30),
    model_year     SMALLINT     CHECK (model_year BETWEEN 1980 AND 2100),
    seats          SMALLINT     NOT NULL CHECK (seats BETWEEN 1 AND 20),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE driver.driver_vehicle_assignments (
    id             UUID         PRIMARY KEY,
    driver_id      UUID         NOT NULL REFERENCES driver.drivers (id),
    vehicle_id     UUID         NOT NULL REFERENCES driver.vehicles (id),
    assigned_at    TIMESTAMPTZ  NOT NULL,
    assigned_by    UUID,
    unassigned_at  TIMESTAMPTZ,
    unassigned_by  UUID,
    CONSTRAINT ck_assignment_period CHECK (unassigned_at IS NULL OR unassigned_at >= assigned_at)
);

-- At most one active assignment per driver and per vehicle.
CREATE UNIQUE INDEX uq_assignment_active_driver ON driver.driver_vehicle_assignments (driver_id)
    WHERE unassigned_at IS NULL;
CREATE UNIQUE INDEX uq_assignment_active_vehicle ON driver.driver_vehicle_assignments (vehicle_id)
    WHERE unassigned_at IS NULL;

CREATE TABLE driver.driver_documents (
    id                 UUID          PRIMARY KEY,
    driver_id          UUID          NOT NULL REFERENCES driver.drivers (id),
    vehicle_id         UUID          REFERENCES driver.vehicles (id),
    type               VARCHAR(30)   NOT NULL CHECK (type IN ('NATIONAL_ID', 'DRIVING_LICENSE', 'PROFILE_PHOTO',
                                                             'VEHICLE_REGISTRATION', 'VEHICLE_INSURANCE')),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    -- File content lives in StorageService (encrypted); only metadata is here.
    storage_key        VARCHAR(200)  NOT NULL UNIQUE,
    content_type       VARCHAR(50)   NOT NULL,
    size_bytes         INTEGER       NOT NULL CHECK (size_bytes > 0),
    sha256             BYTEA         NOT NULL,
    expires_on         DATE,
    uploaded_at        TIMESTAMPTZ   NOT NULL,
    reviewed_by        UUID,
    reviewed_at        TIMESTAMPTZ,
    rejection_reason   TEXT,
    expiry_flagged_at  TIMESTAMPTZ,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_documents_vehicle CHECK (
        (type IN ('VEHICLE_REGISTRATION', 'VEHICLE_INSURANCE')) = (vehicle_id IS NOT NULL)),
    CONSTRAINT ck_documents_review CHECK (
        status NOT IN ('APPROVED', 'REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT ck_documents_rejection CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL)
);

-- One current (not superseded) document per driver, type and vehicle.
CREATE UNIQUE INDEX uq_documents_current ON driver.driver_documents
    (driver_id, type, COALESCE(vehicle_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status <> 'SUPERSEDED';

CREATE INDEX ix_documents_expiry ON driver.driver_documents (expires_on)
    WHERE status = 'APPROVED' AND expiry_flagged_at IS NULL;
