-- Local-only test identities and rolling shift coverage. No rides/quotes/bookings are created.
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('mobility.local-booking-seed'));
DO $$
DECLARE
    passenger_id uuid;
    d record;
    starts timestamptz;
    ends timestamptz;
    day_offset integer;
BEGIN
    IF EXISTS (SELECT 1 FROM identity.users WHERE phone_e164 = '+85510555803'
               AND full_name IS DISTINCT FROM 'Local Booking Passenger') THEN
        RAISE EXCEPTION 'Fixture phone belongs to a different account; refusing to modify it';
    END IF;
    INSERT INTO identity.users(id,phone_e164,full_name,preferred_lang)
    VALUES(gen_random_uuid(),'+85510555803','Local Booking Passenger','en')
    ON CONFLICT(phone_e164) DO NOTHING;
    SELECT id INTO STRICT passenger_id FROM identity.users WHERE phone_e164='+85510555803';
    INSERT INTO identity.user_roles(id,user_id,role)
    VALUES(gen_random_uuid(),passenger_id,'PASSENGER')
    ON CONFLICT ON CONSTRAINT uq_user_roles DO NOTHING;
    IF FOUND THEN
        INSERT INTO audit.audit_logs(id,occurred_at,action,target_type,target_id,details)
        VALUES(gen_random_uuid(),now(),'dev-seed.booking-passenger-created','user',passenger_id::text,
               '{"source":"local-booking-seed"}'::jsonb);
    END IF;
    FOR d IN SELECT id,user_id,full_name,status FROM driver.drivers
             WHERE phone_e164 IN ('+85510555801','+85510555802') ORDER BY id LOOP
        IF d.status <> 'APPROVED' OR d.full_name NOT IN
           ('Local Test Driver 010555801','Local Test Driver 010555802') THEN
            RAISE EXCEPTION 'Unexpected fixture driver state';
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('dispatch.shift:' || d.id::text,0));
        FOR day_offset IN 1..7 LOOP
            starts := ((now() AT TIME ZONE 'Asia/Phnom_Penh')::date + day_offset + time '06:00')
                      AT TIME ZONE 'Asia/Phnom_Penh';
            ends := starts + interval '14 hours';
            IF NOT EXISTS (SELECT 1 FROM dispatch.shift_blocks WHERE driver_id=d.id AND status='ACTIVE'
                           AND starts_at < ends AND ends_at > starts) THEN
                INSERT INTO dispatch.shift_blocks(id,driver_id,starts_at,ends_at,status,created_by,created_at)
                VALUES(gen_random_uuid(),d.id,starts,ends,'ACTIVE',d.user_id,now());
                INSERT INTO audit.audit_logs(id,occurred_at,action,target_type,target_id,details)
                VALUES(gen_random_uuid(),now(),'dev-seed.booking-shift-created','driver',d.id::text,
                       jsonb_build_object('source','local-booking-seed','startsAt',starts,'endsAt',ends));
            END IF;
        END LOOP;
    END LOOP;
END $$;
COMMIT;
