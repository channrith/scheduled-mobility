-- Durable dispatch inbox/work queue. One job per trip makes duplicate event delivery harmless.
CREATE TABLE dispatch.assignment_jobs (
    trip_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'MANUAL')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_reason VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_assignment_jobs_due ON dispatch.assignment_jobs(next_attempt_at, trip_id) WHERE status = 'PENDING';
