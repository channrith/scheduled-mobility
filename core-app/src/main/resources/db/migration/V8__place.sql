-- place module: landmark database (pickups in Phnom Penh are mostly landmark-based) and saved places.

CREATE TABLE place.places (
    id               UUID              PRIMARY KEY,
    name_km          VARCHAR(200),
    name_en          VARCHAR(200),
    latitude         DOUBLE PRECISION  NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude        DOUBLE PRECISION  NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    -- PostGIS point derived from latitude/longitude, for distance queries and spatial indexing.
    location         geography(Point, 4326)
                     GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED,
    place_type       VARCHAR(20)       NOT NULL CHECK (place_type IN ('AIRPORT', 'HOTEL', 'MALL', 'MARKET',
                         'HOSPITAL', 'SCHOOL', 'EMBASSY', 'OFFICE', 'LANDMARK', 'TRANSPORT', 'OTHER')),
    pickup_notes_km  TEXT,
    pickup_notes_en  TEXT,
    usage_count      BIGINT            NOT NULL DEFAULT 0 CHECK (usage_count >= 0),
    -- Staff confirmed the pin and names on the map.
    verified         BOOLEAN           NOT NULL DEFAULT false,
    status           VARCHAR(20)       NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'MERGED')),
    merged_into      UUID              REFERENCES place.places (id),
    created_at       TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ       NOT NULL DEFAULT now(),
    version          BIGINT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_places_name CHECK (name_km IS NOT NULL OR name_en IS NOT NULL),
    CONSTRAINT ck_places_merged CHECK ((status = 'MERGED') = (merged_into IS NOT NULL))
);

CREATE INDEX ix_places_location ON place.places USING gist (location);
-- Trigram indexes for search (pg_trgm handles Khmer with the database's UTF-8 locale).
CREATE INDEX ix_places_name_km_trgm ON place.places USING gin (name_km gin_trgm_ops);
CREATE INDEX ix_places_name_en_trgm ON place.places USING gin (lower(name_en) gin_trgm_ops);

-- A user's own shortcuts. Either a landmark (place_id) or their own pin (e.g. a house) with a note.
CREATE TABLE place.saved_places (
    id            UUID              PRIMARY KEY,
    -- identity.users by value only.
    user_id       UUID              NOT NULL,
    label         VARCHAR(10)       NOT NULL CHECK (label IN ('HOME', 'OFFICE', 'CUSTOM')),
    custom_label  VARCHAR(50),
    place_id      UUID              REFERENCES place.places (id),
    latitude      DOUBLE PRECISION  CHECK (latitude BETWEEN -90 AND 90),
    longitude     DOUBLE PRECISION  CHECK (longitude BETWEEN -180 AND 180),
    note          TEXT,
    created_at    TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ       NOT NULL DEFAULT now(),
    version       BIGINT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_saved_custom_label CHECK ((label = 'CUSTOM') = (custom_label IS NOT NULL)),
    CONSTRAINT ck_saved_target CHECK (place_id IS NOT NULL OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);

-- One Home and one Office per user; any number of custom places.
CREATE UNIQUE INDEX uq_saved_places_home_office ON place.saved_places (user_id, label) WHERE label IN ('HOME', 'OFFICE');
CREATE INDEX ix_saved_places_user ON place.saved_places (user_id);
CREATE INDEX ix_saved_places_place ON place.saved_places (place_id);
