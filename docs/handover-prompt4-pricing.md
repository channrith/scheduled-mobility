# Handover: Prompt 4 — pricing module (+ admin screens)

Written 2026-10-17. For a new Claude Code session that needs Prompt 4's context without the earlier
conversation. Status: **done.** Plan approved 2026-10-17 (all recommendations); implemented, tested and
committed in `6d4e4e3` (backend and console in one commit), pushed to `main`. Read this file and `CLAUDE.md`;
open follow-ups are in section 8. Prompt 3's context is in `docs/handover-prompt3-corporate-place.md`.

---

## 1. Where the project stands

- Done: Prompts 0 (skeleton), 1 (identity), 2 (driver), 2.5 (admin console for driver onboarding),
  3 (corporate + place), 4 (pricing).
- Migrations V1–V10; Prompt 4 added `V10__pricing.sql`.
- At the end of Prompt 4: 169 unit, 229 integration and 2 location-service tests passing; console lint,
  typecheck and build clean; OpenAPI snapshot and `web-console/src/lib/api/schema.ts` regenerated.
- Checked end to end in the browser on the local stack (zone drawn and edited, price set, quote Royal
  Palace → Techo by car: $2.50 + 30.4 km × $0.45 + 73 min × $0.08 + $5.00 airport = $27.02 / 110,800 ៛).
- **Not yet checked on staging:** OSRM routing from the droplet (the tool sandbox could not reach the public
  OSRM server), and staging has no zones or prices yet (section 8).

## 2. The request (Prompt 4, summarized)

**Backend.** `pricing_rules` (city, vehicle_class, base, per_km, per_min, min_fare, fuel_index_factor,
max_surge, optional corporate override, validity period, currency); `zones` with PostGIS polygons, an AIRPORT
zone adds a fixed surcharge; a `RoutingClient` (OSRM over HTTP + straight-line fallback for tests);
`POST /api/v1/quotes` returning quoteId, total (minor units) with KHR equivalent, breakdown (base, distance,
time, airport, fuel), ETA and expiresAt, stored immutably in `fare_quotes`; exchange rate configurable behind
an interface; surge never above max_surge; unit tests incl. corporate overrides and the airport zone; ADMIN
endpoints for rules, zones, fuel index and exchange rate, where a change creates a new rule version (never
edit a rule quotes reference), all audit-logged.
**Web console.** Rules by city and vehicle class (new version with validity date, history); corporate price
overrides on the corporate detail page; zone polygons drawn and edited on the map (AIRPORT, CITY,
RESTRICTED); fuel index and exchange rate with who/when; a quote tester (place search or map, vehicle class,
optional company; breakdown in USD and KHR).

## 3. Decisions (approved 2026-10-17 — all recommendations; don't reopen)

1. **Fuel index:** one global fuel index (1.000 = baseline, set in the console) × each rule's
   `fuel_index_factor` (how strongly that class follows fuel, 0–2). Changing the index needs no new rule
   versions.
2. **Version used by a quote:** the one in force at the **pickup time**, not at quote time.
3. **Zones:** RESTRICTED at either end → no quote; at least one end in a CITY or AIRPORT zone (so city →
   province is quoted, province → province is not); airport surcharge once per ride.
4. **Corporate service fee** (account's `service_fee_pct`): shown with the quote as a separate "invoiced
   service fee", **not** part of the total.
5. **Routing:** staging uses the public OSRM demo server; local runs and tests the straight-line client;
   before production our own OSRM with a Cambodia extract. If OSRM fails the quote is **refused**
   (`pricing.routing-unavailable`, 503), never priced on a guess.
6. **Polygon drawing:** `terra-draw` 1.37.0 + `terra-draw-maplibre-gl-adapter` 1.5.0 (new console deps).
7. **No seed data:** no prices or zone shapes in migrations; they are entered in the console.
8. **City:** a code from configuration (`pricing.cities`, default `PHNOM_PENH`), so Siem Reap needs no
   migration.

## 4. What was built

### 4.1 Schema (`V10__pricing.sql`)
- `pricing.pricing_rules`: one row per version; `seq` identity column orders versions created at the same
  instant; money BIGINT minor units + CHAR(3); `fuel_index_factor` NUMERIC(4,3) 0–2; `max_surge` NUMERIC(4,2)
  1–5; `valid_from`/`valid_to`; `active` (false = a retirement version); `created_by`.
- `pricing.zones`: `geography(Polygon,4326)` + GiST index, `zone_type`, names km/en, `surcharge_minor` +
  `surcharge_currency` (required for AIRPORT only), `active`, `version`.
- `pricing.settings_history`: FUEL_INDEX / USD_KHR_RATE values with `changed_by`, `changed_at`, `seq`.
- `pricing.fare_quotes`: the request, rule id, distance/duration, routing source, fuel index, surge, rate,
  airport zone id, breakdown JSONB, total, KHR/USD equivalent, service fee, ETA, expiry.
- A trigger (`pricing.refuse_change()`) makes `pricing_rules`, `settings_history` and `fare_quotes`
  insert-only in the database itself.

### 4.2 Version rules (`pricing/rule/RuleTimeline.java`)
At time t, the line's version with the latest `valid_from ≤ t` wins (tie: highest `seq`). It applies only if
active and `t < valid_to`. An older version never comes back once a later one has started. Corporate lookup
(`PricingRuleService.select`): the company's version in force, else the city line's (an ended or retired
override falls back to the city price). New versions cannot start in the past (5-minute tolerance).

### 4.3 Fare (`pricing/quote/FareCalculator.java`, pure)
```
subtotal = base + km × per_km + min × per_min
fuel     = subtotal × (fuel_index − 1) × fuel_index_factor
surge    = clamp(requested, 1, max_surge)            (SurgeProvider returns 1 for now)
fare     = max(min_fare, (subtotal + fuel) × surge)
total    = fare + airport surcharge (converted at the quote's rate if in the other currency)
```
BigDecimal throughout; totals round once: USD to the cent, KHR to 100 riel. Lines are rounded to the minor
unit and a ROUNDING line makes them add up exactly. Equivalents use the same rounding
(`FareCalculator.equivalent`). Quote TTL 15 minutes (`pricing.quote-ttl`); ETA = pickup time + duration.

### 4.4 Endpoints
| Endpoint | Who |
|---|---|
| `POST /api/v1/quotes` (pickup/dropoff = `placeId` or `latitude`+`longitude`; `vehicleClass`, `pickupAt`, `corporateId`) | any signed-in user; `corporateId` needs staff or that company's admin/booker |
| `GET /api/v1/quotes/{id}` | the requester or staff (others get 404) |
| `GET /api/v1/admin/pricing/cities` · `GET …/rules?city&vehicleClass&corporateId` · `GET …/rules/history` · `GET …/rules/{id}` | ADMIN, DISPATCHER, SUPPORT |
| `POST …/rules` (new version) · `POST …/rules/retire` | ADMIN |
| `GET …/zones[?city]` · `GET …/zones/{id}` | ADMIN, DISPATCHER, SUPPORT |
| `POST …/zones` · `PUT …/zones/{id}` (`version`) | ADMIN |
| `GET …/settings` (current + last 50 changes with names) | ADMIN, DISPATCHER, SUPPORT |
| `PUT …/settings/fuel-index` (0.5–3) · `PUT …/settings/exchange-rate` (1000–10000) | ADMIN |

Audit actions: `pricing.rule.version-created`, `pricing.rule.retired`, `pricing.zone.created`,
`pricing.zone.updated`, `pricing.setting.changed`. Quotes are not audited (the quote row records who and
when). Errors (km/en in `i18n/pricing*.properties`): `pricing.invalid-field` (+`field`), `rule-not-found`,
`corporate-not-found`, `valid-from-past`, `setting-invalid`, `zone-not-found`, `zone-invalid`, `no-rule`,
`outside-service-area`, `restricted-zone` (+`end`), `routing-unavailable` (503), `no-route`,
`quote-not-found`, `place-not-found`, `location-required` (+`end`), `pickup-time-invalid`,
`corporate-inactive`. Shared bundle gained `error.title.503`.

### 4.5 Other modules touched
- `corporate.CorporateDirectory` (new public API): read-only account summary (status, currency, service
  fee) for pricing.
- Pricing uses `place.PlaceDirectory` (resolves merged places), `identity.Authz`, `identity.UserDirectory`
  (names for "who changed it").

### 4.6 Configuration
`application.yml`: `pricing.cities` (`PRICING_CITIES`), `quote-ttl`, `default-fuel-index`,
`default-usd-khr-rate` (`PRICING_DEFAULT_USD_KHR_RATE`, 4100), `routing.provider` (`ROUTING_PROVIDER`,
default `STRAIGHT_LINE`), `routing.osrm-url` (`OSRM_URL`), `routing.timeout` (5 s), detour factor 1.3 and
25 km/h for the straight line. `application-staging.yml`: OSRM at `https://router.project-osrm.org`.
`RoutingConfiguration` refuses `STRAIGHT_LINE` under a `prod`/`production` profile and requires `OSRM_URL`
with the OSRM provider. No new staging env vars are needed.

### 4.7 Web console
- Sidebar: a second group "Pricing" (`pricingNav` / `navGroups` in `components/shell/nav.ts`); permissions
  `viewPricing` (ADMIN, DISPATCHER, SUPPORT) and `managePricing` (ADMIN) in `lib/auth/roles.ts`.
- Pages: `/pricing/rules`, `/pricing/zones`, `/pricing/settings`, `/pricing/quote-tester`; corporate detail
  gained a "Prices" tab (`features/pricing/corporate-overrides.tsx`) reusing `PriceLines`.
- Maps moved to `features/maps/`: `map-base.ts` (worker URL fix, OSM style, Cambodia bounds, `newMap`),
  `place-map.tsx`, `zone-map.tsx` (terra-draw: polygon mode to draw, select mode to drag corners/midpoints),
  `quote-map.tsx`.
- New shared `components/confirm-dialog.tsx` (`ReasonlessConfirm`).
- Quote tester quotes are real stored quotes (expire after 15 minutes).

## 5. Tests
- Unit: `RuleTimelineTest`, `RuleSelectionTest` (corporate override vs city), `FareCalculatorTest` (each line,
  minimum, fuel weighting, surge cap, airport conversion, riel rounding, lines add up, service fee),
  `ServiceAreaTest` (airport once, service area, restricted, city choice), `OsrmRoutingClientTest` (stub HTTP
  server via the JDK's `HttpServer`: success, no route, errors, timeout; straight line).
- Integration: `PricingRuleIT`, `PricingSettingsIT`, `ZoneIT`, `QuoteIT`.
- Test isolation: `application-test.yml` configures cities `TEST_01`…`TEST_60`; each test class takes one
  from `pricing.TestCities.next()` so rule versions never leak between classes. Zones use
  `PricingApi.randomSpot()` + `square(...)`. Settings are global, so tests that depend on them set them
  first (`QuoteIT` pins fuel index 1 and rate 4100).

## 6. Pitfalls hit in Prompt 4 (avoid repeating)
- **OpenAPI schema names are global** (again): `LineView` existed in two controllers → renamed
  `PriceLineView` / `QuoteLineView`. Before regenerating the spec, check
  `grep -rhoE '\brecord [A-Z][A-Za-z]+' core-app/src/main/java | sort | uniq -d` (only `Entry` and the
  calculator's internal `Money` may repeat; neither is in the API).
- **Timestamp precision:** Java on Linux (CI) reads the clock in nanoseconds, macOS in microseconds, and
  PostgreSQL keeps microseconds — so a create response could differ from later reads, and the test only
  failed on CI. Pricing rounds every stored instant with `shared.time.StoredTime.of(...)` (clock reads and
  client-sent times); tests send nanosecond times on purpose. Use it in new modules too (booking!).
- Insert-only tables need an ordering column (`seq` identity) — the test clock can give rows the same
  timestamp. JPA maps it `insertable = false, updatable = false`; a freshly saved entity has `seq == null`
  (treated as newest).
- PostGIS geography as GeoJSON text works with Hibernate validation:
  `@Column(columnDefinition = "geography") @ColumnTransformer(read = "ST_AsGeoJSON(area)",
  write = "ST_GeomFromGeoJSON(?)::geography")` on a `String`. Validity checked with `ST_IsValid` first.
- RestClient URI templates percent-encode `,` and `;` — OSRM's coordinate separators. The OSRM URL is built
  by hand (coordinates are plain numbers).
- Tokens issued before `MutableClock.advance(...)` expire; issue new ones after moving the clock.
- No Mockito in this codebase: pull decisions into pure static functions and unit-test those.
- 403 from service code uses the existing code `auth.forbidden`.
- `npm run gen:api` after every spec change; `clean verify -pl core-app` removes the jar, so run a full
  `./mvnw verify` (or `package`) before running the jar locally.
- Browser automation: the dev page can resize/scroll while drawing; clicks then land elsewhere (got a
  triangle instead of a square). Re-check with a screenshot after each corner.
- Stop local servers started for testing when done (the user noticed 3000/8080 still running).

## 7. How the user works (unchanged)
Plan first and wait for approval; small steps with tests after each; suggest commit messages; ask before
new libraries; the user runs droplet/GitHub commands and pastes output. Don't touch SMS/OTP. The repo is
public — never write secrets or real staging values into it.

## 8. Follow-ups (not started)
1. **Staging setup after deploy:** draw the city zone (Phnom Penh) and the airport zone (Techo, with its
   surcharge) on `/pricing/zones`, set prices per vehicle class on `/pricing/rules`, check fuel index
   (1.000) and rate (4,100) on `/pricing/settings`. Until zones exist, every quote is
   `outside-service-area`.
2. **Verify OSRM from the droplet:** one quote in the quote tester; no "Straight-line estimate" badge means
   OSRM answered. On `routing-unavailable`, check core-app logs and outbound HTTPS from the droplet.
3. **Own OSRM before production** (public demo server: light use only, no SLA): OSRM container with the
   Geofabrik Cambodia extract; then set `OSRM_URL`.
4. **Driving times are optimistic:** OSRM's car profile for every vehicle class, no traffic. Consider a
   per-class or time-of-day duration factor if quotes come out low (moto/tuk-tuk, rush hour).
5. **Booking (next prompt)** should: accept a `quoteId` before `expiresAt`, check it belongs to the booker,
   call `corporate.PolicyChecker` for corporate rides (quotes don't check member policies), and record place
   usage via `PlaceDirectory.recordUsage`. Consider a public `pricing` API (e.g. a `FareQuotes` lookup) —
   today everything in pricing is internal.
6. **Surge:** `SurgeProvider` returns 1; any real implementation is capped by `max_surge` already.
7. **Map tiles:** still public OSM tiles (fine for a few staff); a hosted provider before wider use (same
   follow-up as Prompt 3).
8. Optional UX: draw-mode undo, showing the route line in the quote tester (OSRM `overview=simplified`),
   toast position (bottom-right) so it doesn't cover header buttons.
