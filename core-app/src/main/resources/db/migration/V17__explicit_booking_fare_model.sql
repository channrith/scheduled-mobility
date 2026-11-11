-- Additive reconciliation: V15/V16 may already exist in a local database; preserve their checksums.
ALTER TABLE booking.ride_requests ADD COLUMN fare_model VARCHAR(20);
-- Translate the prior experimental wire types explicitly, never infer a meter from null destination.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'booking.ride_requests'::regclass AND contype = 'c'
          AND (pg_get_constraintdef(oid) LIKE '%METERED%' OR conname = 'ck_booking_fare_mode')
    LOOP
        EXECUTE format('ALTER TABLE booking.ride_requests DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
UPDATE booking.ride_requests
SET fare_model = CASE WHEN booking_type IN ('METERED', 'AIRPORT_METERED') THEN 'METERED' ELSE 'QUOTED' END,
    booking_type = CASE booking_type WHEN 'METERED' THEN 'ON_DEMAND' WHEN 'AIRPORT_METERED' THEN 'AIRPORT' ELSE booking_type END;
ALTER TABLE booking.ride_requests ALTER COLUMN fare_model SET DEFAULT 'QUOTED';
ALTER TABLE booking.ride_requests ALTER COLUMN fare_model SET NOT NULL;
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_booking_type
    CHECK (booking_type IN ('SCHEDULED', 'AIRPORT', 'RECURRING', 'ON_DEMAND'));
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_booking_fare_model CHECK (fare_model IN ('QUOTED', 'METERED'));
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_ride_requests_flight
    CHECK ((booking_type = 'AIRPORT') = (flight_no IS NOT NULL));
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_booking_meeting_point
    CHECK (meeting_point IS NULL OR booking_type = 'AIRPORT');
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_booking_fare_mode CHECK (
    (fare_model = 'QUOTED' AND quote_id IS NOT NULL AND dropoff_lat IS NOT NULL AND dropoff_lng IS NOT NULL
        AND fare_minor IS NOT NULL AND equivalent_minor IS NOT NULL AND meter_terms IS NULL)
    OR (fare_model = 'METERED' AND booking_type IN ('ON_DEMAND', 'AIRPORT')
        AND quote_id IS NULL AND dropoff_place_id IS NULL AND dropoff_lat IS NULL AND dropoff_lng IS NULL
        AND meter_terms IS NOT NULL AND corporate_id IS NULL AND payment_method = 'CASH')
);
