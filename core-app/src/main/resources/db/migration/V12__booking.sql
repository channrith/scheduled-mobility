-- booking module: a ride request (the booking, made from one fare quote) and its trips (one per occurrence:
-- a recurring booking has one trip per date). Every trip status change writes a trip_events row.

CREATE FUNCTION booking.refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are insert-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TABLE booking.ride_requests (
    id                     UUID              PRIMARY KEY,
    booking_type           VARCHAR(20)       NOT NULL CHECK (booking_type IN ('SCHEDULED', 'AIRPORT', 'RECURRING', 'ON_DEMAND')),
    -- identity.users by value only: who booked, who rides (the same person unless a booker books for a colleague).
    booked_by              UUID              NOT NULL,
    rider_user_id          UUID              NOT NULL,
    -- corporate.* by value only.
    corporate_id           UUID,
    corporate_member_id    UUID,
    cost_center_id         UUID,
    -- pricing.fare_quotes by value only; one booking per quote.
    quote_id               UUID              NOT NULL UNIQUE,
    city                   VARCHAR(30)       NOT NULL,
    vehicle_class          VARCHAR(20)       NOT NULL,
    pickup_place_id        UUID,
    pickup_lat             DOUBLE PRECISION  NOT NULL,
    pickup_lng             DOUBLE PRECISION  NOT NULL,
    pickup_notes           VARCHAR(500),
    -- Storage key of a recorded voice note (upload comes later).
    pickup_voice_note_key  VARCHAR(200),
    dropoff_place_id       UUID,
    dropoff_lat            DOUBLE PRECISION  NOT NULL,
    dropoff_lng            DOUBLE PRECISION  NOT NULL,
    flight_no              VARCHAR(10),
    -- RECURRING: weekdays (e.g. MONDAY,WEDNESDAY) and the last date, in Phnom Penh time.
    recurrence_days        VARCHAR(70),
    recurrence_until       DATE,
    payment_method         VARCHAR(20)       NOT NULL CHECK (payment_method IN ('CASH', 'KHQR', 'CORPORATE_INVOICE')),
    -- The quote's fare, fixed for every trip of the booking; the equivalent is in the other currency.
    currency               CHAR(3)           NOT NULL CHECK (currency IN ('USD', 'KHR')),
    fare_minor             BIGINT            NOT NULL CHECK (fare_minor >= 0),
    equivalent_currency    CHAR(3)           NOT NULL CHECK (equivalent_currency IN ('USD', 'KHR')),
    equivalent_minor       BIGINT            NOT NULL CHECK (equivalent_minor >= 0),
    service_fee_minor      BIGINT,
    created_at             TIMESTAMPTZ       NOT NULL,
    version                BIGINT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_ride_requests_flight CHECK ((booking_type = 'AIRPORT') = (flight_no IS NOT NULL)),
    CONSTRAINT ck_ride_requests_recurrence CHECK ((booking_type = 'RECURRING') = (recurrence_days IS NOT NULL AND recurrence_until IS NOT NULL)),
    CONSTRAINT ck_ride_requests_corporate CHECK ((corporate_id IS NULL) = (payment_method <> 'CORPORATE_INVOICE'))
);

CREATE INDEX ix_ride_requests_booked_by ON booking.ride_requests (booked_by);
CREATE INDEX ix_ride_requests_rider ON booking.ride_requests (rider_user_id);
CREATE INDEX ix_ride_requests_corporate ON booking.ride_requests (corporate_id) WHERE corporate_id IS NOT NULL;

CREATE TABLE booking.trips (
    id                    UUID          PRIMARY KEY,
    ride_request_id       UUID          NOT NULL REFERENCES booking.ride_requests (id),
    -- 1 for a single ride; 1..n for a recurring series.
    occurrence            INT           NOT NULL CHECK (occurrence >= 1),
    pickup_at             TIMESTAMPTZ   NOT NULL,
    status                VARCHAR(30)   NOT NULL CHECK (status IN ('SCHEDULED', 'ASSIGNED', 'DRIVER_EN_ROUTE', 'DRIVER_ARRIVED',
                              'IN_PROGRESS', 'COMPLETED', 'CANCELLED_BY_PASSENGER', 'CANCELLED_BY_DRIVER', 'CANCELLED_BY_OPS',
                              'NO_SHOW', 'DISPUTED')),
    -- driver.* by value only.
    driver_id             UUID,
    vehicle_id            UUID,
    assigned_at           TIMESTAMPTZ,
    en_route_at           TIMESTAMPTZ,
    arrived_at            TIMESTAMPTZ,
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    ended_at              TIMESTAMPTZ,
    -- Cash the driver says the rider paid (CASH trips), and whether it differs from the fare.
    cash_collected_minor  BIGINT        CHECK (cash_collected_minor >= 0),
    cash_currency         CHAR(3)       CHECK (cash_currency IN ('USD', 'KHR')),
    cash_reported_at      TIMESTAMPTZ,
    cash_mismatch         BOOLEAN,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_trips_occurrence UNIQUE (ride_request_id, occurrence),
    CONSTRAINT ck_trips_driver CHECK ((status IN ('ASSIGNED', 'DRIVER_EN_ROUTE', 'DRIVER_ARRIVED', 'IN_PROGRESS')) <= (driver_id IS NOT NULL))
);

CREATE INDEX ix_trips_status_pickup ON booking.trips (status, pickup_at);
CREATE INDEX ix_trips_driver_pickup ON booking.trips (driver_id, pickup_at) WHERE driver_id IS NOT NULL;
CREATE INDEX ix_trips_ride ON booking.trips (ride_request_id);

-- One row per status change (from_status NULL = the trip was created).
CREATE TABLE booking.trip_events (
    id             UUID          PRIMARY KEY,
    seq            BIGINT        GENERATED ALWAYS AS IDENTITY UNIQUE,
    trip_id        UUID          NOT NULL REFERENCES booking.trips (id),
    from_status    VARCHAR(30),
    to_status      VARCHAR(30)   NOT NULL,
    actor_user_id  UUID,
    actor_role     VARCHAR(20)   NOT NULL CHECK (actor_role IN ('RIDER', 'BOOKER', 'DRIVER', 'OPS', 'SYSTEM')),
    reason         VARCHAR(500),
    driver_id      UUID,
    occurred_at    TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_trip_events_trip ON booking.trip_events (trip_id, seq);

CREATE TRIGGER tr_trip_events_insert_only BEFORE UPDATE OR DELETE ON booking.trip_events
    FOR EACH ROW EXECUTE FUNCTION booking.refuse_change();
