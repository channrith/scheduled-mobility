# Passenger API reconciliation report — 2026-11-06

The required implementations were already present as uncommitted working-tree
changes when this handoff was received. They were reconciled against the pasted
contract and verified. The linked `passenger-location-api-proposal.md` and
`phnom-penh-simulator-testing.md` were not available in this repository; no unseen
contracts are claimed as verified.

## Contracts

| Endpoint | Final behavior |
| --- | --- |
| `GET /api/v1/places/reverse?lat=...&lng=...` | Authenticated pin labeling, English/Khmer fallback, original coordinates retained, internal UUID only at an exact active catalog coordinate. No place/saved-place creation. |
| `GET /api/v1/places/search` | Adds nullable `formattedAddressEn` and `formattedAddressKm`; existing IDs, names, coordinates and pickup notes remain distinct. |
| `POST /api/v1/quotes`, `GET /api/v1/quotes/{quoteId}` | Nullable persisted `route` with polyline encoding and precision 5 from the same OSRM response used for pricing. GET does not reroute. Existing root fare/distance/time/expiry fields remain. |
| `POST /api/v1/booking/availability` | Personal eligibility only: type, fare model, vehicle, pickup, applicable destination/time. Returns available, city, endpoint zone types, optional airport UUID and localized stable reasons. No quote/ride creation or router call. |

Full examples, reason semantics and integration instructions are in
[handover-passenger-locations.md](handover-passenger-locations.md).
The generated machine contract is [openapi.json](openapi.json).

Reverse lookup: invalid input is 400; explicit provider no-result is 200 with
`resolved=false`, null labels and original coordinates; rate limits are 429
`places.geocoding-rate-limited` with `Retry-After: 60`; outages/malformed provider
responses/timeouts are 503 `places.geocoding-unavailable`. Outages/rate limits are
not cached. Reverse responses and eligibility responses send `Cache-Control:
no-store`. Bounded internal label caching remains separate from HTTP caching.

Availability: malformed combinations are 400; business rejection is 200 with
`available=false` and localized reason codes; database/transaction outage is 503
`booking.availability-unavailable`. A supplied Idempotency-Key does not replay
stale preflight results. Quote and booking endpoints still revalidate.
RECURRING is not supported by this personal preflight; existing recurring booking
behavior remains separate. Flight and meeting-point validation still belongs to
booking rather than this location-only preflight.

## Review fixes

- Preserve upstream HTTP 429 as a rate-limit problem instead of a generic outage.
- Prevent HTTP caching of reverse and availability responses.
- Make constructor rollout defaults false, matching application YAML; no mode is
  enabled automatically.
- Represent route geometry as an OpenAPI object-or-null union, and explicitly
  describe nullable reverse-label and search-address fields.
- Verify delayed lookups retain their own pin/language and preflight creates no
  rides. Clients must still suppress responses for an earlier UI selection.

## Migration and provider setup

Additive V18 adds nullable `place.places.formatted_address_en/km` and
`pricing.fare_quotes.route` JSONB. No existing address, landmark coordinate,
coverage polygon or quote geometry is backfilled. Apply through normal Flyway
startup after reviewing the existing V15–V17 local migration work.

Set `GEOCODING_REVERSE_URL` to an operator-controlled Nominatim-compatible reverse
endpoint without query parameters; `GEOCODING_TIMEOUT` defaults to `3s`. There is
no implicit public geocoder. Without configuration, exact catalog coordinates
work and other pins return a recoverable 503. Existing OSRM routing configuration
and failure policy are retained.

The repeatable local seed adds a clearly named, unverified simulator landmark at
11.3633,104.9211 inside the demo KTI rectangle. It preserves the original Techo
entry at 11.3650,104.8620 and does not expand coverage. Neither coordinate nor the
demo polygon is asserted to be surveyed or approved production data. The seed
was exercised only in a disposable test database during this review.

## Verification and source state

Base revision: `abbed5073504dc1c63fc4be4a0b32341c3a8fe10`. The working tree was
already dirty with booking/pricing work and remains uncommitted. Tests use an
isolated `/tmp` source snapshot because shared build outputs disappeared during
a concurrent workspace run. A source fingerprint is recorded in
[passenger-api-verification-sources.json](passenger-api-verification-sources.json).
At report completion, Git reported 41 tracked modifications and 36 untracked
paths (directory entries may contain multiple files). This includes pre-existing
work; it is not a count of changes made by this review. All 425 fingerprinted
backend source/resource/test files matched the verified snapshot.

Java 21 focused verification passed: **18 unit/module checks and 14 integration
checks; one optional simulator-preview check skipped; zero failures/errors**.

Unit/module suites: `BookingAvailabilityControllerTest`, `ModularityTests`,
`GeocodingProviderTest`, `OsrmRoutingClientTest`.

Integration suites: `PassengerLocationIT`, `ReversePlacesIT`, `QuoteGeometryIT`,
`LocalPricingFixtureIT`, `BookingModesIT`, `BookingFareMigrationIT`,
`OpenApiSnapshotIT`.

Coverage includes explicit no-result versus outage and timeout, rate limits,
English/Khmer label behavior, delayed response identity, pin preservation,
address/notes separation, eligibility without ride/quote creation, stale
idempotency replay avoidance, city/airport/restricted/outside/mixed coverage,
effective fare availability, disabled rollout gates, old quoted callers,
stored route consistency and ownership, migrations and repeatable seed behavior.
Module boundaries, structural checks and seed shell syntax are also checked.

After the final OpenAPI-only correction, the three module checks and the OpenAPI
snapshot integration check passed again. Generated console API types were
refreshed, `npm run typecheck` passed, and `git diff --check` passed.

Focused backend command (run from the isolated repository snapshot with Java 21):

```bash
./mvnw -o -pl core-app -am \
  -Dtest=GeocodingProviderTest,BookingAvailabilityControllerTest,OsrmRoutingClientTest,ModularityTests \
  -Dit.test=PassengerLocationIT,ReversePlacesIT,QuoteGeometryIT,LocalPricingFixtureIT,BookingModesIT,BookingFareMigrationIT,OpenApiSnapshotIT \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dopenapi.update=true verify
```

The final OpenAPI rerun uses `-Dtest=ModularityTests` and
`-Dit.test=OpenApiSnapshotIT` with the same remaining flags. Test logs are retained
locally at `/tmp/passenger-snapshot-verification.log` and
`/tmp/passenger-contract-verification.log`. Earlier shared-target failures are not
reported as passing runs; verification was repeated successfully in isolation.

## Remaining integration work

- Configure and verify the contracted geocoding provider and its operating limits.
- Integrate mobile request cancellation/stale-response suppression and the new
  nullable fields; real-device/provider end-to-end verification is outstanding.
- Review the actual Techo landmark and airport coverage with operators.
- P2 passenger place-detail and coverage-overlay endpoints are deferred.

No commit, push, deployment, running-service restart, live migration, or real
passenger booking was performed by this review.
