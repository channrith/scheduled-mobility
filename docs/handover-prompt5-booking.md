# Handover: Prompt 5 — booking module (+ transactional outbox)

Written 2026-10-18. For a new Claude Code session that needs Prompt 5's context without the earlier
conversation. Status: **built and tested locally, not yet committed** (suggested commits in section 9). Read this
file and `CLAUDE.md`; earlier context is in `docs/handover-prompt4-pricing.md` (pricing) and
`docs/handover-prompt3-corporate-place.md` (corporate, place). Open follow-ups are in section 8.

---

## 1. Where the project stands

- Done: Prompts 0 (skeleton), 1 (identity), 2 (driver), 2.5 (admin console), 3 (corporate + place),
  4 (pricing, on staging with OSRM), 5 (booking + outbox, this file).
- Migrations V1–V12; Prompt 5 added `V11__outbox.sql` and `V12__booking.sql`.
- At the end of Prompt 5: 182 unit, 244 integration and 2 location-service tests passing; OpenAPI snapshot
  regenerated and stable; `web-console/src/lib/api/schema.ts` regenerated (console typecheck and lint clean).
- **No console screens** for booking (decision 10). Booking is API-only; try it with the API docs page on staging
  or curl.

## 2. The request (Prompt 5, summarized)

`booking.ride_requests`, `booking.trips`, `booking.trip_events`. Booking types SCHEDULED, AIRPORT (flight_no),
RECURRING (weekday series), ON_DEMAND (model only). `POST /api/v1/rides` from a quoteId with pickup notes / voice
note key, payment method, optional cost centre and rider; quote unexpired, corporate policy checked,
Idempotency-Key required. Trip state machine SCHEDULED → ASSIGNED → DRIVER_EN_ROUTE → DRIVER_ARRIVED →
IN_PROGRESS → COMPLETED + CANCELLED_BY_PASSENGER / _BY_DRIVER / _BY_OPS, NO_SHOW, DISPUTED; guarded transitions, a
trip_events row each, optimistic locking. Events RideBooked, TripAssigned, TripStatusChanged, TripCompleted,
TripCancelled through an outbox table; a relay publishing to RabbitMQ and marking events published. Driver
endpoints (arrived, start, complete, cash); passenger/booker endpoints (get, upcoming, cancel). Tests: happy
path, invalid transitions, concurrent assignment, idempotent retry.

## 3. Decisions (approved 2026-10-18 — all recommendations; don't reopen)

1. **Outbox location:** one table `outbox.outbox_events` in a new `outbox` schema (shared infrastructure, like
   `audit`), written through `shared.outbox.Outbox`, one relay.
2. **Driver assignment until dispatch exists:** dispatchers assign by hand through `/api/v1/ops/trips/...`; the
   dispatch engine will later call the same `TripService`. Backup drivers come with dispatch.
3. **A driver dropping a trip** → back to SCHEDULED (driver cleared) for reassignment. CANCELLED_BY_DRIVER only
   set by ops (`byDriver: true`) when nobody can replace the driver.
4. **RECURRING:** the one quote's fare for every occurrence; at most 90 days; all trips created at booking; the
   first trip is the quote's own pickup (its weekday must be in `days`); the policy is checked for every date and
   any refusal rejects the whole booking (the refused dates are listed).
5. **Payment:** CASH and CORPORATE_INVOICE; KHQR refused (`booking.payment-method-unavailable`) until the payment
   module; a corporate quote must be CORPORATE_INVOICE and only a corporate quote may be.
6. **Corporate rides** are booked by the company's admin/booker (or staff) for an active member
   (`riderMemberId`, default the booker's own membership). Riders booking for themselves: later (they can't get
   a corporate quote today).
7. **Rider/booker cancel** until the driver has arrived; no cancellation fees yet.
8. **Cash:** any amount accepted once per completed CASH trip; it matches if it equals the fare or the fare's
   equivalent in the other currency (the riel equivalent is rounded to 100 ៛), otherwise `cash_mismatch = true`.
   Extra event **TripCashReported** for the payment module.
9. **Voice notes:** option A — store a storage key only (pattern-checked); upload, playback and deletion later.
10. **No console screens** in this prompt (option A); dispatcher screens come with the dispatch module.
11. **No new libraries:** Spring AMQP publisher confirms; Spring Modulith's event registry was considered and not
    used (two new dependencies, no explicit relay).

## 4. What was built

### 4.1 Outbox (`V11__outbox.sql`, `core-app/.../shared/outbox/`)
- `outbox.outbox_events`: id, `seq` (identity, publishing order), event_type (record simple name), routing_key,
  aggregate_type, aggregate_id, payload JSONB, occurred_at, published_at, attempts, next_attempt_at, last_error.
- `Outbox.publish(routingKey, aggregateType, aggregateId, event)`: `Propagation.MANDATORY` (throws outside a
  transaction), inserts the row and also publishes the event in-process (`ApplicationEventPublisher`), so
  `CapturedEvents` and future in-app listeners see it.
- `OutboxRelay.relayBatch()`: `@Scheduled(fixedDelay 1s)` (off in tests; tests call it). One transaction: up to
  100 rows `WHERE published_at IS NULL AND (next_attempt_at IS NULL OR <= now) ORDER BY seq FOR UPDATE SKIP LOCKED`;
  each sent and **marked published only after the publisher confirm** (ack within 5 s); a failure sets
  attempts + 1, `next_attempt_at` = now + 1 s, 2 s, 4 s … ≤ 5 min, `last_error`. `deletePublished()` daily
  03:30 Phnom Penh, retention 7 days.
- Message: persistent JSON envelope `{eventId, type, aggregateType, aggregateId, occurredAt, payload}`;
  `message-id` = event id (consumers drop duplicates by it), `type` = event type.
- `OutboxConfiguration` declares the durable topic exchange `mobility.events`. `application.yml`:
  `spring.rabbitmq.publisher-confirm-type: correlated`. `OutboxProperties` (`outbox.*`): exchange, batch-size,
  confirm-timeout, max-backoff, retention; `outbox.relay-interval`, `outbox.cleanup-cron`.

### 4.2 Schema (`V12__booking.sql`)
- `booking.ride_requests`: type, booked_by, rider_user_id, corporate_id / corporate_member_id / cost_center_id,
  `quote_id UNIQUE`, city, vehicle_class, pickup (place id, lat/lng, notes ≤ 500, voice_note_key), drop-off,
  flight_no, recurrence_days (`MONDAY,WEDNESDAY`) + recurrence_until, payment_method, fare_minor + currency,
  equivalent_minor + equivalent_currency, service_fee_minor, created_at, version. Checks: AIRPORT ⇔ flight_no;
  RECURRING ⇔ recurrence; corporate ⇔ CORPORATE_INVOICE.
- `booking.trips`: ride_request_id, occurrence (unique per ride), pickup_at, status, driver_id, vehicle_id,
  a timestamp per step (assigned, en_route, arrived, started, completed, ended), cash (amount, currency,
  reported_at, mismatch), created/updated, **version**. Check: an active status needs a driver.
- `booking.trip_events`: insert-only (trigger `booking.refuse_change()`), `seq`, from_status (NULL = created),
  to_status, actor_user_id, actor_role (RIDER, BOOKER, DRIVER, OPS, SYSTEM), reason, driver_id, occurred_at.

### 4.3 State machine (`booking/trip/TripStateMachine.java`)
| From | To |
|---|---|
| SCHEDULED | ASSIGNED, CANCELLED_BY_PASSENGER, CANCELLED_BY_OPS |
| ASSIGNED | DRIVER_EN_ROUTE, SCHEDULED, CANCELLED_BY_PASSENGER / _OPS / _DRIVER |
| DRIVER_EN_ROUTE | DRIVER_ARRIVED, SCHEDULED, CANCELLED_BY_PASSENGER / _OPS / _DRIVER |
| DRIVER_ARRIVED | IN_PROGRESS, NO_SHOW, CANCELLED_BY_OPS |
| IN_PROGRESS | COMPLETED, CANCELLED_BY_OPS |
| COMPLETED | DISPUTED |
| DISPUTED | COMPLETED |

`Trip.moveTo` checks it (409 `booking.invalid-transition` with `from`/`to`) and stamps the time; back to
SCHEDULED clears driver, vehicle, assigned/en-route times. **Every change goes through `TripService`**
(`change`/`record`): `saveAndFlush` (version check → 409 `concurrent-update` via the shared handler), a
`trip_events` row, `TripStatusChanged` + the specific event, in one transaction. Assign also takes the
dispatcher's `version` (stale → 409). NO_SHOW only 10 min after both pickup time and arrival
(`booking.no-show-too-early`, `earliest`). Ops reasons ≥ 10 characters (`booking.reason-required`).

### 4.4 Booking (`booking/ride/BookingService.java`)
Order of checks: quote exists and `requestedBy` = caller (else 404, no leak) → not expired (422) → not used
(409; plus a unique-constraint backstop mapped to 409) → type rules (ON_DEMAND 422; AIRPORT flight
`^[A-Z0-9]{2}\s?\d{1,4}[A-Z]?$` normalized like `K6 123`, quote must touch the airport zone; RECURRING via
`Recurrence.pickups`) → payment → notes/voice key → corporate (`Authz.canAccessCorporate`, rider member active,
cost centre of the company and active, `PolicyChecker` per pickup) → save ride + trips (each with a created
trip_events row) → `RideBooked` → audit `booking.ride.booked` when staff booked. Fare, route, pickup time and
vehicle class all come from the quote. Times use `StoredTime.of(...)` (microseconds).

### 4.5 Endpoints
| Endpoint | Who |
|---|---|
| `POST /api/v1/rides` (header `Idempotency-Key` required → else 400 `booking.idempotency-key-required`) | signed-in; corporate: that company's admin/booker or staff |
| `GET /api/v1/rides` (upcoming open trips, from 1 h ago, paged) · `GET /api/v1/rides/{id}` (trips + history) | booker, rider; company admins/bookers; staff |
| `POST /api/v1/rides/{id}/cancel` (`tripId`? `reason`?) | booker, rider, company admins/bookers |
| `GET /api/v1/driver/trips` · `POST …/{tripId}/en-route` · `/arrived` · `/start` · `/complete` · `/no-show` · `/cash` | DRIVER, own trips only (another driver's → 404) |
| `GET /api/v1/ops/trips?status&from&to&page` · `GET …/{id}` · `POST …/{id}/assign` · `/unassign` · `/cancel` · `/dispute` · `/resolve` | ADMIN, DISPATCHER; audit `booking.trip.*` |

Assign: driver APPROVED with a current ACTIVE vehicle of the ride's class, else 422 `booking.driver-unavailable`
(`reason`: NOT_APPROVED, NO_VEHICLE, VEHICLE_CLASS). Errors (km/en, `i18n/booking*.properties`): quote-not-found,
quote-expired, quote-used, type-not-available, not-an-airport-trip, invalid-field (`field`), recurrence-invalid
(`field`), payment-method-unavailable, payment-method-invalid, rider-invalid, cost-center-invalid,
policy-refused (`refused`: `[{pickupAt, violations}]`), ride-not-found, trip-not-found, nothing-to-cancel,
invalid-transition, driver-not-found, driver-unavailable, no-show-too-early, not-cash, not-completed,
cash-already-reported, reason-required, not-a-driver, idempotency-key-required.

### 4.6 Events (`booking/BookingEvents.java`; routing keys `booking.<kebab-name>`)
RideBooked (rideId, type, corporateId, tripIds, firstPickupAt, currency, fareMinor, paymentMethod) ·
TripAssigned (tripId, rideId, driverId, vehicleId, pickupAt) · TripStatusChanged (from, to, actorRole) on every
change · TripCompleted (driver, fare, payment method, corporateId; not on dispute resolution) · TripCancelled
(any CANCELLED_* and NO_SHOW, with the driver before) · TripCashReported (amount, currency, fare, mismatch). No
personal data in payloads.

### 4.7 New public APIs in other modules
- `pricing.FareQuotes.find(quoteId)` → `QuoteSummary` (impl `pricing/quote/FareQuotesService`).
- `driver.DriverDirectory.get(driverId)` / `byUserId(userId)` → `DriverInfo` (status, current ACTIVE vehicle;
  impl `driver/vehicle/DriverDirectoryService`).
- `corporate.CorporateMembers.member(corpId, memberId)` / `memberByUser` / `costCenter` (scoped by company;
  impl `corporate/member/CorporateMembersService`).

## 5. Tests
- Unit: `TripStateMachineTest` (7), `RecurrenceTest` (3).
- Integration: `OutboxIT` (4: order + ids + marked published, MANDATORY/rollback, retry after the exchange is
  deleted, cleanup), `BookingFlowIT` (4: happy path with history, audit, 9 outbox events delivered to a real
  RabbitMQ queue bound to `booking.#`; invalid transitions; **two concurrent assigns → {200, 409}**, one ASSIGNED
  row, one TripAssigned; **same Idempotency-Key → same ride, `Idempotent-Replayed: true`**, new key → 409, no key
  → 400), `BookingRulesIT` (7: quote/payment rules incl. expiry; corporate policy, rider, cost centre,
  visibility; airport; recurring + partial and full cancel; who sees what; ops unassign/dispute/resolve/cancel;
  cash match in riel and mismatch).
- Helpers: `booking.BookingApi` (its own `TestCities` city with CITY + AIRPORT zones and a CAR price; quotes,
  bookings, assign, driver steps), `driver.DriverApi.approvedDriver()` (APPROVED with a CAR).

## 6. Pitfalls hit in Prompt 5 (avoid repeating)
- **Jackson 3 refuses a missing primitive** in a request record (`boolean byDriver` → 400). Use `Boolean` for
  optional flags. `corporate`'s `UpdateCostCenterRequest(boolean active)` has the same trap (fine today: the
  console always sends it).
- **RabbitMQ auto-delete queues** (`AnonymousQueue`) vanish after the first `receive()`; tests use a plain named
  queue and delete it in `@AfterEach`.
- Test classes that share a costly setup (zones + price) keep it in a `static` helper created once per class.
- Record names are global in the OpenAPI spec: `DriverDirectory.DriverSummary` clashed with the driver views →
  `DriverInfo`; booking views use their own names (`RidePointView`, `TripReasonRequest`, `OpsCancelRequest`…).
  Check `grep -rhoE '\brecord [A-Z][A-Za-z]+' core-app/src/main/java | sort | uniq -d` (only `Entry` and
  `Money` may repeat; neither is in the API).
- Times stored through `StoredTime.of(...)` (see the Prompt 4 handover) — booking does this everywhere.

## 7. How the user works (unchanged)
Plan first and wait for approval; small steps with tests after each; suggest commit messages; ask before new
libraries; the user runs droplet/GitHub commands and pastes output. Don't touch SMS/OTP. The repo is public —
never write secrets or real staging values into it. Stop local servers started for testing when done.

## 8. Follow-ups (not started)
1. **Commit and deploy** (section 9). On staging, check the RabbitMQ user may declare the `mobility.events`
   exchange (core-app log at startup); no new env vars are needed.
2. **Nobody consumes the events yet**: RabbitMQ drops unrouted messages, so events are confirmed and discarded
   until notification/payment bind queues (`booking.#`). The outbox keeps them 7 days.
3. **Dispatch (next prompt)**: the engine assigns through `TripService.assign`; backup drivers for SLA trips;
   overlap check (today a driver can be assigned two trips at the same time); live driver state in Redis; the
   **dispatcher console** (trips needing a driver, assign/unassign, exceptions, trip history).
4. **Payment module**: consume TripCompleted / TripCashReported into the double-entry ledger; KHQR via the bank
   gateway webhook; corporate monthly invoices (service fee from the ride).
5. **Voice notes (option B)**: upload, temporary playback link for booker/rider/assigned driver/ops, deletion
   about 30 days after the trip.
6. **Riders booking corporate rides themselves**: needs corporate quotes for RIDER members (pricing checks
   `canAccessCorporate`, which covers admins/bookers only).
7. Cancellation fees, flight tracking for AIRPORT rides, ON_DEMAND, recurring series longer than 90 days.
8. Traffic time factor for pricing (business decision pending; plan in the "How our fares are calculated" doc).

## 9. Suggested commits
```bash
git add core-app/src/main/resources/db/migration/V11__outbox.sql core-app/src/main/java/com/mobility/core/shared/outbox \
        core-app/src/test/java/com/mobility/core/shared/outbox core-app/src/main/resources/application.yml
git commit -m "feat(shared): transactional outbox with a RabbitMQ relay (publisher confirms, retries, cleanup)"

git add core-app web-console/src/lib/api/schema.ts docs/openapi.json docs/handover-prompt5-booking.md
git commit -m "feat(booking): ride requests, trips with a guarded state machine, trip events, outbox events; driver, rider and ops endpoints"
```
Each message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
