-- Local development fixtures only. Approximate zones and sample USD-cent fares;
-- these are not approved service boundaries or commercial tariffs.
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('mobility.local-pricing-seed'));

INSERT INTO pricing.zones (id, city, zone_type, name_en, area, surcharge_minor, surcharge_currency)
SELECT gen_random_uuid(), 'PHNOM_PENH', z.kind, z.name,
       ST_GeogFromText(z.polygon), z.surcharge, z.currency
FROM (VALUES
 ('CITY', 'Local demo - Phnom Penh',
  'POLYGON((104.75 11.40,105.05 11.40,105.05 11.75,104.75 11.75,104.75 11.40))', NULL::bigint, NULL::text),
 ('AIRPORT', 'Local demo - Phnom Penh airport (PNH)',
  'POLYGON((104.825 11.535,104.855 11.535,104.855 11.565,104.825 11.565,104.825 11.535))', 200, 'USD'),
 ('AIRPORT', 'Local demo - Techo airport (KTI)',
  'POLYGON((104.90 11.33,104.96 11.33,104.96 11.39,104.90 11.39,104.90 11.33))', 300, 'USD')
) AS z(kind, name, polygon, surcharge, currency)
WHERE NOT EXISTS (SELECT 1 FROM pricing.zones existing
                  WHERE existing.city = 'PHNOM_PENH' AND existing.zone_type = z.kind);

-- Preserve every existing price line, including intentionally retired lines.
INSERT INTO pricing.pricing_rules
 (id, city, vehicle_class, currency, base_minor, per_km_minor, per_min_minor,
  min_fare_minor, fuel_index_factor, max_surge, valid_from, active)
SELECT gen_random_uuid(), 'PHNOM_PENH', r.vehicle, 'USD', r.base, r.km, r.minute,
       r.minimum, 0, 1, now(), true
FROM (VALUES
 ('MOTO', 50, 20, 2, 100),
 ('TUKTUK_REMORK', 100, 35, 3, 150),
 ('TUKTUK_RICKSHAW', 75, 30, 3, 125),
 ('CAR', 150, 50, 5, 250),
 ('SUV', 200, 70, 7, 350),
 ('VAN', 300, 90, 10, 500)
) AS r(vehicle, base, km, minute, minimum)
WHERE NOT EXISTS (SELECT 1 FROM pricing.pricing_rules existing
                  WHERE existing.city = 'PHNOM_PENH'
                    AND existing.vehicle_class = r.vehicle AND existing.corporate_id IS NULL);

-- Persist the demo settings so they are visible in Pricing > Settings.
-- Never replace settings already chosen by an operator.
INSERT INTO pricing.settings_history (id, setting, value)
SELECT gen_random_uuid(), s.setting, s.value
FROM (VALUES ('FUEL_INDEX', 1.0000::numeric),
             ('USD_KHR_RATE', 4100.0000::numeric)) AS s(setting, value)
WHERE NOT EXISTS (SELECT 1 FROM pricing.settings_history existing
                  WHERE existing.setting = s.setting);
-- Explicit coordinate-only simulator landmark. Never move the existing unverified
-- Techo catalog entry or widen any zone to make it qualify.
WITH fixture AS (
INSERT INTO place.places
 (id,name_en,name_km,latitude,longitude,place_type,verified,formatted_address_en,formatted_address_km)
VALUES ('7ca0c1ad-7f1e-40e5-a371-aef081d2d017',
        'Local demo - KTI zone test point (not surveyed)',
        'ចំណុចសាកល្បងតំបន់ KTI',11.3633,104.9211,'AIRPORT',false,
        'Local simulator fixture only; not a surveyed airport terminal',
        'ទីតាំងសម្រាប់សាកល្បងប៉ុណ្ណោះ')
ON CONFLICT (id) DO NOTHING RETURNING id
)
INSERT INTO audit.audit_logs (id,occurred_at,action,target_type,target_id,details)
SELECT gen_random_uuid(),now(),'dev-seed.airport-fixture-created','place',id::text,
       '{"source":"local-development-seed","surveyed":false}'::jsonb FROM fixture;
COMMIT;
