-- dispatch module: drivers' shift blocks, the primary + backup driver of each trip with their confirmations,
-- risk tiers, an insert-only assignment history and the exception queue. Trips themselves live in booking.

CREATE FUNCTION dispatch.refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are insert-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END
$$;

-- When a driver says they can drive. Active blocks of one driver never overlap (checked under a per-driver lock).
CREATE TABLE dispatch.shift_blocks (
    id            UUID          PRIMARY KEY,
    -- driver.drivers by value only.
    driver_id     UUID          NOT NULL,
    starts_at     TIMESTAMPTZ   NOT NULL,
    ends_at       TIMESTAMPTZ   NOT NULL,
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_by    UUID          NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    cancelled_at  TIMESTAMPTZ,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_shift_blocks_order CHECK (ends_at > starts_at)
);

CREATE INDEX ix_shift_blocks_driver ON dispatch.shift_blocks (driver_id, starts_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_shift_blocks_time ON dispatch.shift_blocks (starts_at, ends_at) WHERE status = 'ACTIVE';

-- One row per trip that dispatch has handled. The primary driver mirrors booking.trips.driver_id.
CREATE TABLE dispatch.trip_assignments (
    -- booking.trips by value only.
    trip_id               UUID          PRIMARY KEY,
    primary_driver_id     UUID,
    primary_deadline      TIMESTAMPTZ,
    primary_confirmed_at  TIMESTAMPTZ,
    backup_driver_id      UUID,
    backup_deadline       TIMESTAMPTZ,
    backup_confirmed_at   TIMESTAMPTZ,
    reason_code           VARCHAR(30),
    note                  VARCHAR(500),
    assigned_by           UUID,
    assigned_at           TIMESTAMPTZ,
    risk_tier             VARCHAR(10)   CHECK (risk_tier IN ('GREEN', 'AMBER', 'RED')),
    slack_seconds         BIGINT,
    risk_reason           VARCHAR(20),
    risk_evaluated_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_trip_assignments_backup CHECK (backup_driver_id IS NULL OR backup_driver_id IS DISTINCT FROM primary_driver_id)
);

CREATE INDEX ix_trip_assignments_primary ON dispatch.trip_assignments (primary_driver_id) WHERE primary_driver_id IS NOT NULL;
CREATE INDEX ix_trip_assignments_backup ON dispatch.trip_assignments (backup_driver_id) WHERE backup_driver_id IS NOT NULL;

CREATE TABLE dispatch.assignment_history (
    id                  UUID          PRIMARY KEY,
    seq                 BIGINT        GENERATED ALWAYS AS IDENTITY UNIQUE,
    trip_id             UUID          NOT NULL,
    action              VARCHAR(30)   NOT NULL,
    role                VARCHAR(10)   CHECK (role IN ('PRIMARY', 'BACKUP')),
    driver_id           UUID,
    previous_driver_id  UUID,
    reason_code         VARCHAR(30),
    note                VARCHAR(500),
    actor_user_id       UUID,
    occurred_at         TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_assignment_history_trip ON dispatch.assignment_history (trip_id, seq);

CREATE TRIGGER tr_assignment_history_insert_only BEFORE UPDATE OR DELETE ON dispatch.assignment_history
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_change();

-- The exception queue: at most one open exception per trip and type; a worse condition raises its severity.
CREATE TABLE dispatch.exceptions (
    id                      UUID          PRIMARY KEY,
    exception_type          VARCHAR(30)   NOT NULL CHECK (exception_type IN ('UNASSIGNED', 'UNCONFIRMED', 'BACKUP_UNCONFIRMED',
                                'DRIVER_DECLINED', 'AT_RISK', 'NO_BACKUP')),
    trip_id                 UUID          NOT NULL,
    driver_id               UUID,
    severity                VARCHAR(10)   NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    status                  VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'AUTO_RESOLVED')),
    -- Copied from the trip, for sorting the queue.
    pickup_at               TIMESTAMPTZ   NOT NULL,
    assigned_dispatcher_id  UUID,
    details                 JSONB         NOT NULL DEFAULT '{}',
    opened_at               TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    resolved_at             TIMESTAMPTZ,
    resolved_by             UUID,
    resolution_note         VARCHAR(500),
    version                 BIGINT        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exceptions_open ON dispatch.exceptions (trip_id, exception_type) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX ix_exceptions_queue ON dispatch.exceptions (status, pickup_at);
