-- Additive passenger address and immutable quote display snapshot fields.
ALTER TABLE place.places ADD COLUMN formatted_address_en varchar(500),
                         ADD COLUMN formatted_address_km varchar(500);
ALTER TABLE pricing.fare_quotes ADD COLUMN route jsonb;
ALTER TABLE pricing.fare_quotes ADD CONSTRAINT ck_quote_route_size
 CHECK (route IS NULL OR octet_length(route::text) <= 210000);
