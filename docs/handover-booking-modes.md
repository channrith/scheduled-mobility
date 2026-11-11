> Phase 2 backend reconciliation supersedes the backend enum and rollout notes below: [current backend contract](phase2-booking-contract.md). BookingType has exactly four values; FareModel is separate. Passenger UI/adapters are unchanged in Phase 2.

> Compatibility baseline: see the [Phase 1 architecture audit](booking-architecture-audit.md). The implementation notes below describe earlier uncommitted work, including extra BookingType values that conflict with the required four-value enum. They are not an approved or deployed contract.

# Handover: passenger booking modes

Local implementation, 2026-11-01, based on HEAD `59af9077fcdfe72aa5843417d175b1ee8c5f3ed8`. No commit, deployment or real booking mutation. Existing user authorization explicitly extended the previous scheduled-only scope to immediate and metered modes.

BookingType retains SCHEDULED, AIRPORT, RECURRING, ON_DEMAND and adds METERED and AIRPORT_METERED. ON_DEMAND is now implemented using a NOW quote. Destination-free services do not manufacture quotes, destinations or fares. The live passenger app has a typed four-mode structure with associated fixed/metered airport selection; RECURRING remains existing backend-only UI scope.

## API and data

- GET `/api/v1/booking/options`: supported modes and scheduling policy (future time, minimum notice zero, maximum 366 days, Phnom Penh display timezone). The backend BookingPolicy is constructor-injected.
- POST `/api/v1/rides`: METERED requires pickup/vehicle/CASH and no quoteId, destination or pickupAt. AIRPORT_METERED additionally requires future pickupAt, flightNo and meetingPoint and validates the pickup airport zone. Quoted modes use quoteId; no direct route/time/vehicle overrides are accepted.
- New passenger clients send contractVersion=2. AIRPORT v2 requires a meeting point and airport pickup; older AIRPORT clients retain existing transfer semantics. Meeting point is stored separately from notes and returned in reads, including driver detail.
- NOW versus SCHEDULED is recorded on the immutable quote based on omitted/explicit pickupAt. Booking cannot reinterpret timing, and unexpired NOW quotes do not acquire an arbitrary earlier cutoff.
- POST `/api/v1/driver/meter/location`: DRIVER only, stable Idempotency-Key, authenticated driver's in-progress metered trip only. It does not accept a client fare, total distance or trip id. No active meter is a no-op.
- GET `/api/v1/driver/trips/{tripId}`: driver-owned trip plus ride data, including nullable destination and fare. Driver iOS reloads this after metered completion before cash reporting.
- V15 adds nullable quote/destination/fare, meeting point, immutable metered pricing terms, aggregate meter session and constraints, plus a unique in-progress trip per driver. V16 records quote pickup timing. No old migration was edited.

Backend meter pricing terms (tariff/fuel/surge/exchange/airport surcharge) are captured at booking through the public pricing boundary. Accepted GPS fixes drive server distance and server trip timestamps drive elapsed time. The existing BigDecimal/integer-minor-unit FareCalculator finalizes the fare atomically with COMPLETED and the existing outbox events. Fixed quoted fares remain immutable. Replays cannot add distance. A persisted movement anchor prevents losing slow travel while filtering noise.

Current meter policy: finite geographic coordinates; accuracy (0,50] m; sample age at most 30 s / future tolerance 2 s; chronological timestamps at least 1 s apart; first fix within 30 s of start, subsequent coverage gaps at most 30 s; final fix age at most 15 s; plausible speed <=45 m/s; duration at most 24 h. Invalid or incomplete measurements return a conflict and leave the fare absent. Existing audited ops cancellation can close an incomplete trip; no arbitrary fare correction/override or synthetic distance is introduced. Calibration, fraud resistance and field/background-loss testing are release work; do not call this a certified taximeter.

Dispatch suggestions exclude unknown endpoints and conservatively withhold drivers holding destination-free commitments. Manual dispatch remains available. No pickup is substituted as dropoff. Existing gateway `/api/v1/**` routing covers the new endpoints, and method role checks remain in core-app.

## Rollout

Metered services default off: `BOOKING_METERED_ENABLED=false`. Deploy migrations/core and compatible driver/passenger clients, verify driver meter delivery and perform field testing, then explicitly enable `BOOKING_METERED_ENABLED=true`. The server both advertises and enforces the flag. Test profile enables metered services only for disposable integration fixtures. No environment or deployment was changed here.

Old mobile clients may not decode absent fare/destination; roll out compatible readers before enabling metered bookings. Updated iOS readers represent absent values explicitly and never substitute zero money or fake coordinates. The pending booking journal persists exact bytes and keys for either quoted or metered requests, scoped by account/origin. Future scheduling is now explicitly enforced; callers must not use a NOW quote for default SCHEDULED booking.

OpenAPI and generated web-console types were updated. No console product screens or new dependencies were added. iOS contract snapshots identify the current HEAD plus uncommitted working-tree state rather than pretending these changes are deployed.

## Checks

Full backend run during implementation: platform-security 13 unit, core 208 unit and 267 integration tests passed. Subsequent final booking, pricing, dispatch and OpenAPI suites passed 40 executed tests (the optional simulator hold test skips normally). Final OpenAPI generation/verification is run after enum documentation/order changes. Passenger iOS has 61 passing tests, driver 54; both existing Debug schemes build on iOS 26.2 with iOS 15 deployment targets retained. Physical GPS calibration, iOS 15 runtime and production integration were not verified.

BookingModesIT covers metered null fields, exact-key replay, destination rejection, future/flight/meeting/airport-zone validation, immediate quoted pricing after six minutes while still valid, authenticated meter readings, finalization, bad jumps and missing coverage. Existing booking, pricing, assignment, planning and risk suites remain in use.

The opt-in `BookingModesIT#simulatorPreview` exposes a temporary real Testcontainers core server and a private `/tmp/mobility-booking-preview.json` file for the iOS loopback test bridge. It completed successfully in a separate run. The bridge scripts only OTP UI; mobility calls hit real controllers/database/Redis. A synthetic metered booking and cancellation and airport form variants were inspected on Mobility Socket QA / iOS 26.2. No real SMS/user/payment/reservation was used. The private file and containers are removed when the hold test exits.
