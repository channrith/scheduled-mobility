-- pricing module: versioned fare rules, zones (airport surcharge, service area, restricted areas),
-- fuel index / exchange rate history and immutable fare quotes. Money: BIGINT minor units + CHAR(3).

-- Rows are never changed or removed: quotes reference rule versions, and history must stay as it was.
CREATE FUNCTION pricing.refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are insert-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END
$$;

-- One row per version of a price line (city + vehicle class, optionally one corporate client).
-- The version in force at time t: the line's latest valid_from <= t (highest seq wins a tie); it
-- applies if active and t < valid_to. A later version replaces it; retiring a line is a version with
-- active = false.
CREATE TABLE pricing.pricing_rules (
    id                 UUID           PRIMARY KEY,
    -- Creation order, also between versions created at the same instant.
    seq                BIGINT         GENERATED ALWAYS AS IDENTITY UNIQUE,
    city               VARCHAR(30)    NOT NULL,
    vehicle_class      VARCHAR(20)    NOT NULL CHECK (vehicle_class IN ('MOTO', 'TUKTUK_REMORK', 'TUKTUK_RICKSHAW',
                           'CAR', 'SUV', 'VAN')),
    -- corporate.corporate_accounts by value only; NULL = the city price.
    corporate_id       UUID,
    currency           CHAR(3)        NOT NULL CHECK (currency IN ('USD', 'KHR')),
    base_minor         BIGINT         NOT NULL CHECK (base_minor >= 0),
    per_km_minor       BIGINT         NOT NULL CHECK (per_km_minor >= 0),
    per_min_minor      BIGINT         NOT NULL CHECK (per_min_minor >= 0),
    min_fare_minor     BIGINT         NOT NULL CHECK (min_fare_minor >= 0),
    -- How strongly this line follows the fuel index: 0 = not at all, 1 = fully.
    fuel_index_factor  NUMERIC(4, 3)  NOT NULL CHECK (fuel_index_factor BETWEEN 0 AND 2),
    max_surge          NUMERIC(4, 2)  NOT NULL CHECK (max_surge BETWEEN 1 AND 5),
    valid_from         TIMESTAMPTZ    NOT NULL,
    valid_to           TIMESTAMPTZ,
    active             BOOLEAN        NOT NULL,
    -- identity.users by value only.
    created_by         UUID,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_pricing_rules_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE INDEX ix_pricing_rules_line ON pricing.pricing_rules (city, vehicle_class, corporate_id, valid_from DESC, seq DESC);
CREATE INDEX ix_pricing_rules_corporate ON pricing.pricing_rules (corporate_id) WHERE corporate_id IS NOT NULL;

CREATE TRIGGER tr_pricing_rules_insert_only BEFORE UPDATE OR DELETE ON pricing.pricing_rules
    FOR EACH ROW EXECUTE FUNCTION pricing.refuse_change();

-- CITY: the service area (one end of a ride must be in a CITY or AIRPORT zone). AIRPORT: adds a fixed
-- surcharge once per ride. RESTRICTED: no pickup or drop-off. Quotes copy what they used, so zones are
-- edited in place (with an audit entry).
CREATE TABLE pricing.zones (
    id                  UUID                      PRIMARY KEY,
    city                VARCHAR(30)               NOT NULL,
    zone_type           VARCHAR(20)               NOT NULL CHECK (zone_type IN ('CITY', 'AIRPORT', 'RESTRICTED')),
    name_km             VARCHAR(200),
    name_en             VARCHAR(200),
    area                geography(Polygon, 4326)  NOT NULL,
    surcharge_minor     BIGINT                    CHECK (surcharge_minor >= 0),
    surcharge_currency  CHAR(3)                   CHECK (surcharge_currency IN ('USD', 'KHR')),
    active              BOOLEAN                   NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ               NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ               NOT NULL DEFAULT now(),
    version             BIGINT                    NOT NULL DEFAULT 0,
    CONSTRAINT ck_zones_name CHECK (name_km IS NOT NULL OR name_en IS NOT NULL),
    CONSTRAINT ck_zones_surcharge CHECK ((zone_type = 'AIRPORT') = (surcharge_minor IS NOT NULL AND surcharge_currency IS NOT NULL))
);

CREATE INDEX ix_zones_area ON pricing.zones USING gist (area);

-- Fuel index (1.000 = baseline) and the USD→KHR rate. The current value is the newest row; before the
-- first change the configured default applies.
CREATE TABLE pricing.settings_history (
    id          UUID            PRIMARY KEY,
    -- Insertion order: the newest row is the current value.
    seq         BIGINT          GENERATED ALWAYS AS IDENTITY UNIQUE,
    setting     VARCHAR(30)     NOT NULL CHECK (setting IN ('FUEL_INDEX', 'USD_KHR_RATE')),
    value       NUMERIC(12, 4)  NOT NULL CHECK (value > 0),
    changed_by  UUID,
    changed_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX ix_settings_history_setting ON pricing.settings_history (setting, seq DESC);

CREATE TRIGGER tr_settings_history_insert_only BEFORE UPDATE OR DELETE ON pricing.settings_history
    FOR EACH ROW EXECUTE FUNCTION pricing.refuse_change();

-- A fare quote is final: the fare is fixed at quote time and this row is never changed.
CREATE TABLE pricing.fare_quotes (
    id                    UUID              PRIMARY KEY,
    requested_by          UUID              NOT NULL,
    corporate_id          UUID,
    city                  VARCHAR(30)       NOT NULL,
    vehicle_class         VARCHAR(20)       NOT NULL,
    pickup_place_id       UUID,
    pickup_lat            DOUBLE PRECISION  NOT NULL,
    pickup_lng            DOUBLE PRECISION  NOT NULL,
    dropoff_place_id      UUID,
    dropoff_lat           DOUBLE PRECISION  NOT NULL,
    dropoff_lng           DOUBLE PRECISION  NOT NULL,
    pickup_at             TIMESTAMPTZ       NOT NULL,
    rule_id               UUID              NOT NULL REFERENCES pricing.pricing_rules (id),
    distance_meters       BIGINT            NOT NULL CHECK (distance_meters >= 0),
    duration_seconds      BIGINT            NOT NULL CHECK (duration_seconds >= 0),
    routing_source        VARCHAR(20)       NOT NULL,
    fuel_index            NUMERIC(12, 4)    NOT NULL,
    surge                 NUMERIC(4, 2)     NOT NULL,
    usd_khr_rate          NUMERIC(12, 4)    NOT NULL,
    airport_zone_id       UUID,
    -- Breakdown lines (BASE, DISTANCE, TIME, FUEL_INDEX, SURGE, MINIMUM_FARE, AIRPORT, ROUNDING) in currency.
    breakdown             JSONB             NOT NULL,
    currency              CHAR(3)           NOT NULL CHECK (currency IN ('USD', 'KHR')),
    total_minor           BIGINT            NOT NULL CHECK (total_minor >= 0),
    equivalent_currency   CHAR(3)           NOT NULL CHECK (equivalent_currency IN ('USD', 'KHR')),
    equivalent_minor      BIGINT            NOT NULL CHECK (equivalent_minor >= 0),
    -- Corporate service fee shown with the quote; invoiced separately, not part of total_minor.
    service_fee_pct       NUMERIC(5, 2),
    service_fee_minor     BIGINT,
    eta                   TIMESTAMPTZ       NOT NULL,
    expires_at            TIMESTAMPTZ       NOT NULL,
    created_at            TIMESTAMPTZ       NOT NULL DEFAULT now()
);

CREATE INDEX ix_fare_quotes_requested_by ON pricing.fare_quotes (requested_by, created_at DESC);
CREATE INDEX ix_fare_quotes_rule ON pricing.fare_quotes (rule_id);

CREATE TRIGGER tr_fare_quotes_insert_only BEFORE UPDATE OR DELETE ON pricing.fare_quotes
    FOR EACH ROW EXECUTE FUNCTION pricing.refuse_change();
