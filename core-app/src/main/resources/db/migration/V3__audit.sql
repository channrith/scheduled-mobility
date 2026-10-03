-- Append-only log of staff/admin actions. Never store PII in details.
CREATE TABLE audit.audit_logs (
    id             UUID          PRIMARY KEY,
    occurred_at    TIMESTAMPTZ   NOT NULL,
    actor_user_id  UUID,
    actor_roles    TEXT[]        NOT NULL DEFAULT '{}',
    action         VARCHAR(100)  NOT NULL,
    target_type    VARCHAR(50)   NOT NULL,
    target_id      VARCHAR(100)  NOT NULL,
    details        JSONB         NOT NULL DEFAULT '{}'
);

CREATE INDEX ix_audit_logs_target ON audit.audit_logs (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_actor ON audit.audit_logs (actor_user_id, occurred_at DESC);

CREATE FUNCTION audit.reject_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_logs is append-only';
END;
$$;

CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();
