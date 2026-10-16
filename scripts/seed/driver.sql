-- Local development only. Re-runnable; preserves existing profiles and statuses.
-- Run against the local Compose database (see docs/postman/README.md).
BEGIN;
DO $$
DECLARE
    account_id uuid;
    driver_id uuid;
BEGIN
    INSERT INTO identity.users (id, phone_e164, full_name)
    VALUES (gen_random_uuid(), '+85512898339', 'Test Driver 012898339')
    ON CONFLICT (phone_e164) DO NOTHING;
    SELECT id INTO STRICT account_id FROM identity.users WHERE phone_e164 = '+85512898339';

    INSERT INTO identity.user_roles (id, user_id, role)
    VALUES (gen_random_uuid(), account_id, 'DRIVER')
    ON CONFLICT ON CONSTRAINT uq_user_roles DO NOTHING;
    IF FOUND THEN
        INSERT INTO audit.audit_logs (id, occurred_at, action, target_type, target_id, details)
        VALUES (gen_random_uuid(), now(), 'dev-seed.driver-role-granted', 'user', account_id::text,
                '{"source":"local-development-seed","role":"DRIVER"}');
    END IF;

    INSERT INTO driver.drivers (id, user_id, full_name, phone_e164, status)
    SELECT gen_random_uuid(), id, COALESCE(full_name, 'Test Driver 012898339'), phone_e164, 'PENDING'
    FROM identity.users WHERE id = account_id
    ON CONFLICT (user_id) DO NOTHING
    RETURNING id INTO driver_id;
    IF driver_id IS NOT NULL THEN
        INSERT INTO driver.driver_status_history (id, driver_id, to_status, reason, occurred_at)
        VALUES (gen_random_uuid(), driver_id, 'PENDING', 'Local development seed', now());
        INSERT INTO audit.audit_logs (id, occurred_at, action, target_type, target_id, details)
        VALUES (gen_random_uuid(), now(), 'dev-seed.driver-registered', 'driver', driver_id::text,
                '{"source":"local-development-seed","status":"PENDING"}');
    END IF;
END $$;
COMMIT;
