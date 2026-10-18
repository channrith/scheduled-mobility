-- Transactional outbox: modules write an event row in the same transaction as their change; the relay
-- publishes unpublished rows to RabbitMQ and marks them published. Shared infrastructure, like audit.
CREATE SCHEMA IF NOT EXISTS outbox;

CREATE TABLE outbox.outbox_events (
    id               UUID           PRIMARY KEY,
    -- Publishing order.
    seq              BIGINT         GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_type       VARCHAR(100)   NOT NULL,
    routing_key      VARCHAR(150)   NOT NULL,
    aggregate_type   VARCHAR(50)    NOT NULL,
    aggregate_id     UUID           NOT NULL,
    payload          JSONB          NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL,
    published_at     TIMESTAMPTZ,
    attempts         INT            NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       VARCHAR(500)
);

CREATE INDEX ix_outbox_unpublished ON outbox.outbox_events (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_aggregate ON outbox.outbox_events (aggregate_id, seq);
CREATE INDEX ix_outbox_published ON outbox.outbox_events (published_at) WHERE published_at IS NOT NULL;
