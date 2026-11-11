# Passenger location and eligibility APIs

Implementation based on the iOS passenger proposal dated 2026-11-06. Additive
working-tree changes on base revision `abbed5073504dc1c63fc4be4a0b32341c3a8fe10`.
Existing uncommitted booking-mode work is retained. No rollout flags are changed.
No commit, deployment, or real booking is part of this change.

## New reverse lookup

```http
GET /api/v1/places/reverse?lat=11.5697&lng=104.9210
Authorization: Bearer <passenger-token>
Accept-Language: en
```

```json
{"placeId":null,"name":"Example label","formattedAddress":"Example address","latitude":11.5697,"longitude":104.9210,"pickupInstructions":null,"resolved":true}
```

The response always retains the input coordinates. Only an exact active catalog
coordinate can return an internal `placeId`; nearby pins never inherit that ID.
Canonical catalog names, addresses and pickup instructions fall back between
English and Khmer. Other pins use the configured provider language preference
(`en,km` or `km,en`). Provider IDs are discarded. No places are created.

Configure `GEOCODING_REVERSE_URL` as a self-hosted or contracted
Nominatim-compatible **/reverse endpoint without query parameters**. The adapter
requests `format=jsonv2`, `lat`, `lon`, and `accept-language`, consuming `name` and
`display_name`. It does not contact a public server implicitly. Configure provider
network access/credentials at your controlled endpoint/proxy. `GEOCODING_TIMEOUT`
defaults to `3s`. Restart core-app after changing environment settings.

Without a configured provider, exact catalog pins work; all other pins return
503. This is an explicit integration gap until an operator configures a provider.
The provider's explicit `Unable to geocode` result yields 200, resolved=false,
null labels and original coordinates. Generic 404s, malformed payloads, upstream
errors and timeouts yield 503 `places.geocoding-unavailable`. Outages aren't cached.

Redis enforces 60 lookups/user/minute and 30 upstream lookups/minute globally.
429 uses `places.geocoding-rate-limited` with `Retry-After: 60`. Redis/database
failures return 503. The process-local cache is capped at 1,000 entries, keyed by
exact coordinates and language: 5-minute positive TTL, 30-second no-result TTL.
Provider responses are capped at 256 KiB and label fields at 500 characters.
No raw pins, provider bodies or credentials are logged by this feature.

Upstream provider HTTP 429 also returns the localized rate-limit problem with
`Retry-After: 60`, rather than being disguised as an outage. Neither rate limits
nor outages populate the lookup cache. HTTP reverse responses use
`Cache-Control: no-store`; the bounded internal lookup cache is separate.

## Search addresses

`GET /api/v1/places/search` adds nullable `formattedAddressEn` and
`formattedAddressKm`. These are distinct from `pickupNotesEn/Km`. Search names,
ranking, UUIDs and paired `lat/lng` semantics remain intact. Nonfinite/out-of-range
search coordinates now fail with 400 instead of entering PostGIS.

Admin create/update/read also support those address fields (max 500 characters).
For updates, omission/null preserves an existing address; an empty string clears
it. Existing clients and internal constructors remain compatible.

## Quote route snapshot

`POST /api/v1/quotes` and `GET /api/v1/quotes/{quoteId}` add nullable `route`:

```json
{"encoding":"polyline","precision":5,"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"}
```

OSRM requests full overview polyline geometry in the same request that calculates
fare distance/time. Geometry is bounded at 100,000 characters and persisted in
`pricing.fare_quotes.route` JSONB. Missing/oversized geometry is null; root
`distanceMeters`/`durationSeconds` stay authoritative. STRAIGHT_LINE and older
quotes return null. GET never reroutes. Quote ownership, expiry, integer money,
UTC timestamps, and existing routing-error behavior are unchanged.

## Eligibility preflight

```http
POST /api/v1/booking/availability
Authorization: Bearer <passenger-token>
Content-Type: application/json
Accept-Language: en

{"type":"SCHEDULED","fareModel":"QUOTED","vehicleClass":"CAR","pickup":{"latitude":11.5697,"longitude":104.9210},"dropoff":{"latitude":11.5480,"longitude":104.9343},"pickupAt":"<fresh future UTC ISO-8601 timestamp>"}
```

```json
{"available":true,"city":"PHNOM_PENH","pickupZoneTypes":["CITY"],"dropoffZoneTypes":["CITY"],"airportZoneId":null,"reasons":[]}
```

This is personal eligibility, not a driver, fare, route, or reservation guarantee.
It creates no quotes/rides and calls no router. It bypasses the mutation
idempotency cache so even a supplied key cannot replay stale eligibility.
Quote and booking endpoints revalidate authoritatively.

Preflight responses also use `Cache-Control: no-store`. Delayed reverse responses
retain the coordinates and language of their own request; the client must use
request identity/cancellation to discard an earlier pin's response after a newer
selection. The backend cannot determine which request the UI currently selects.

- SCHEDULED/QUOTED requires both endpoints and future `pickupAt`.
- ON_DEMAND/QUOTED requires both endpoints and forbids `pickupAt`.
- ON_DEMAND/METERED requires pickup only and forbids destination/time.
- AIRPORT requires future time and an airport **pickup**, consistent with the
  current contractVersion=2 booking guard; quoted also requires destination.
- RECURRING isn't a personal preflight mode. Missing/invalid shapes return 400.
- placeId retains precedence over coordinates, as in quote creation.
- Availability uses the same rollout gates as booking/options and booking,
  shared quote area/rule selection, and authoritative metered preparation.
- One covered endpoint can qualify a quoted route; restricted endpoints reject
  it. Pickup-only metered eligibility doesn't invent a destination.

Business rejection returns 200 with `available=false` and a `reasons` array of
`code`, nullable `end`, nullable `field`, and localized `detail`. Reuses
`booking.type-not-available`, `booking.invalid-field` (time),
`booking.not-an-airport-trip`, `pricing.outside-service-area`,
`pricing.restricted-zone`, `pricing.no-rule`, and place lookup errors.
Malformed input is 400; auth stays 401/403; infrastructure outage is 503
`booking.availability-unavailable`. On rejection, coverage metadata is empty;
`reasons` is authoritative. No corporate-policy or flight/meeting-field guarantee
is made by this personal location preflight.

New error messages are provided in English and Khmer. Full schemas and response
statuses are generated in `docs/openapi.json`.

## Migration and controlled airport setup

Start the updated core-app normally; Flyway applies additive V18, adding nullable
address columns and route JSONB. Old records remain readable; no data backfill
or operator-owned coordinate/zone update occurs.

Then run locally:

```bash
./scripts/seed-pricing.sh
```

Requires V18 first. The existing seed remains repeatable and preserves settings,
zones and fares already present. It additionally inserts a fixed-UUID landmark
`7ca0c1ad-7f1e-40e5-a371-aef081d2d017`, named **Local demo - KTI zone test point
(not surveyed)** at 11.3633,104.9211. It is unverified and explicitly marked as a
simulator fixture, inside the local KTI rectangle. It does not move the original
Techo landmark at 11.3650,104.8620 or expand any production/development polygon.
The original catalog entry's real-world coordinate remains pending operator
review; use the clearly named fixture or coordinates for simulator airport tests.

The seed script targets only this repository's local Compose database. Approximate
zones and prices remain demo data, not surveyed boundaries/commercial tariffs.
If operator-owned zones differ, verify fixture containment before using it.

## Deferred and integration limits

P2 passenger place details and coverage polygon APIs are deferred. No provider
subscription, credentials, real-world airport survey, production meter, rollout
change, deployed API, or live iOS end-to-end test is claimed. Client debounce,
cancellation and stale-response suppression stay in iOS. The backend preserves
pin coordinates and doesn't persist a selected passenger pin in reverse lookup.

## Verification

Full backend dependency-reactor verification:

```bash
./mvnw -B -ntp -pl core-app -am verify -Dopenapi.update=true
```

Passed on 2026-11-06: platform-security 13 unit tests; core-app 218 unit tests;
277 integration tests (0 failures/errors, 1 optional `booking.preview.seconds`
simulator-preview test skipped). This includes existing auth, ownership, quote
expiry, idempotency and booking-mode regression tests. All mutations in these
integration tests use isolated Testcontainers databases, not live passenger data.

A subsequent focused contract run checks final unique OpenAPI record names and
503 transaction-outage mapping, with the following tests:
`ModularityTests`, `GeocodingProviderTest`, `OsrmRoutingClientTest`,
`BookingAvailabilityControllerTest`, `PassengerLocationIT`, `LocalPricingFixtureIT`,
`ReversePlacesIT`, `QuoteGeometryIT`, `OpenApiSnapshotIT`.
The focused tests cover pin identity, independent address/notes, language fallback,
no-result/outage/timeouts, rate limiting, cache absence versus outage, no stale
preflight replay with an Idempotency-Key, schedule/shape gates, city/airport/
restricted/mixed/outside areas, missing fares, quote ownership, null straight-line
geometry, OSRM geometry persistence with no rerouting, and seed repeatability.

Repository structural checks (`scripts/check.sh --no-backend --no-console`),
seed shell syntax, and `git diff --check` pass. OpenAPI lives in
`docs/openapi.json`; the web-console client types are regenerated from it.

Working-tree status: implementation and generated artifacts remain uncommitted
alongside the pre-existing booking-mode changes. No existing work was reverted.
The running local services and local database have not been restarted/migrated by
this API implementation task. Apply the startup/migration/seed steps above to
activate it locally. No provider subscription or deployment is configured.

Final focused run: **18 unit tests + 7 integration tests passed**, 0 failures,
0 errors, 0 skips. Web-console `npm run gen:api`, `npm run lint`, and
`npm run typecheck` passed. No web-console production build was run for these
schema-only changes.

The final source fingerprint matches
`docs/passenger-api-verification-sources.json`:
`43ac6a45450b9fd09720b78e2a3d71cca6abb255c2833c9e7a1cd3bb900d7591`
(424 files under core-app/src and platform-security/src). This identifies the
uncommitted source beyond the base Git revision. Generated OpenAPI and client
types correspond to the final focused snapshot test. HTTP responses use
`Cache-Control: no-store` for reverse lookup and preflight; reverse integration
tests also cover out-of-order provider responses retaining their own pins.
