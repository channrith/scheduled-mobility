-- Fixed quotes remain immutable; destination-free metered bookings have no fare until completion.
ALTER TABLE booking.ride_requests DROP CONSTRAINT ride_requests_booking_type_check;
ALTER TABLE booking.ride_requests ADD CHECK (booking_type IN ('SCHEDULED','AIRPORT','RECURRING','ON_DEMAND','METERED','AIRPORT_METERED'));
ALTER TABLE booking.ride_requests DROP CONSTRAINT ck_ride_requests_flight;
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_ride_requests_flight CHECK ((booking_type IN ('AIRPORT','AIRPORT_METERED')) = (flight_no IS NOT NULL));
ALTER TABLE booking.ride_requests ALTER COLUMN quote_id DROP NOT NULL;
ALTER TABLE booking.ride_requests ALTER COLUMN dropoff_lat DROP NOT NULL;
ALTER TABLE booking.ride_requests ALTER COLUMN dropoff_lng DROP NOT NULL;
ALTER TABLE booking.ride_requests ALTER COLUMN fare_minor DROP NOT NULL;
ALTER TABLE booking.ride_requests ALTER COLUMN equivalent_minor DROP NOT NULL;
ALTER TABLE booking.ride_requests ADD COLUMN meter_terms JSONB;
ALTER TABLE booking.ride_requests ADD COLUMN meeting_point VARCHAR(200);
ALTER TABLE booking.ride_requests ADD CHECK (meeting_point IS NULL OR booking_type IN ('AIRPORT','AIRPORT_METERED'));
ALTER TABLE booking.ride_requests ADD CONSTRAINT ck_booking_fare_mode CHECK (
    (booking_type IN ('METERED','AIRPORT_METERED') AND quote_id IS NULL AND dropoff_place_id IS NULL AND dropoff_lat IS NULL AND dropoff_lng IS NULL AND meter_terms IS NOT NULL AND corporate_id IS NULL)
    OR (booking_type NOT IN ('METERED','AIRPORT_METERED') AND quote_id IS NOT NULL AND dropoff_lat IS NOT NULL AND dropoff_lng IS NOT NULL AND fare_minor IS NOT NULL AND equivalent_minor IS NOT NULL AND meter_terms IS NULL));
ALTER TABLE booking.ride_requests ADD CHECK ((fare_minor IS NULL) = (equivalent_minor IS NULL));
CREATE TABLE booking.meter_sessions (
    trip_id UUID PRIMARY KEY REFERENCES booking.trips(id),
    last_at TIMESTAMPTZ, first_at TIMESTAMPTZ, last_lat DOUBLE PRECISION, last_lng DOUBLE PRECISION,
    last_accuracy DOUBLE PRECISION, position_at TIMESTAMPTZ, distance_meters DOUBLE PRECISION NOT NULL DEFAULT 0,
    samples BIGINT NOT NULL DEFAULT 0, finalized BOOLEAN NOT NULL DEFAULT false,
    version BIGINT NOT NULL DEFAULT 0
);

-- A physical driver cannot operate two trips concurrently, including concurrent start requests.
CREATE UNIQUE INDEX uq_driver_in_progress ON booking.trips(driver_id) WHERE status='IN_PROGRESS' AND driver_id IS NOT NULL;
