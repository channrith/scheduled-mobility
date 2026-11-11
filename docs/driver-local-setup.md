# Driver setup and assigned-trip test guide

Local setup verified 2026-11-11. This is an API/client integration guide, not a claim that a driver mobile UI
or push-notification service has been implemented. Run examples only against your local test environment.

## 1. Driver fixtures and local services

| Account | Phone | Driver profile UUID | CAR plate |
| --- | --- | --- | --- |
| Candidate 1 | `010555801` / `+85510555801` | `ea8e0cf8-4554-44c3-a569-3685f9cf7fe5` | `LOCAL-SEED-010555801` |
| Candidate 2 | `010555802` / `+85510555802` | `5e483106-47a6-48be-82c1-7c8ccc33641a` | `LOCAL-SEED-010555802` |

Both have APPROVED onboarding, an active CAR and four approved unexpired **synthetic** documents. They have
DRIVER roles, not ADMIN. Shift coverage is **November 12–18, 2026, 06:00–20:00 Phnom Penh time**.
A driver's profile UUID differs from their login user UUID. The authenticated API resolves both; do not
substitute one for the other when inspecting Redis keys.

- Gateway: `http://localhost:8090`; core-app: 8080; location-service: 8081.
- All three services and local PostgreSQL/Redis/RabbitMQ must be running.
- Restart IDE core-app from the current source with `DISPATCH_AUTOMATIC_ENABLED=true` for automatic matching.
- Run `./scripts/seed-booking.sh` only when fixtures need creating/extending. It preserves existing pricing,
  adds missing future shifts, does not create bookings, and leaves the normal running services untouched.
- A physical phone uses your development machine's reachable address rather than the phone's `localhost`.

[Seed details](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/local-booking-seed.md) · [Passenger setup](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/passenger-local-setup.md)

## 2. Login and verify identity

Examples use Bash, curl and jq. Pick one driver account per terminal/client. Use the existing local OTP
configuration; there is no seeded password and no new fixed OTP set by the fixture script.

```bash
API_BASE=http://localhost:8090
DRIVER_PHONE=010555801
curl --fail-with-body "$API_BASE/api/v1/auth/otp/request" \
  -H 'Content-Type: application/json' -H 'Accept-Language: en' \
  --data "{\"phone\":\"$DRIVER_PHONE\"}"

# Enter the current local OTP; input is hidden.
read -r -s OTP_CODE
printf '\n'
DRIVER_AUTH=$(curl --fail-with-body "$API_BASE/api/v1/auth/otp/verify" \
  -H 'Content-Type: application/json' \
  --data "{\"phone\":\"$DRIVER_PHONE\",\"code\":\"$OTP_CODE\"}")
DRIVER_TOKEN=$(printf '%s' "$DRIVER_AUTH" | jq -er '.accessToken')
DRIVER_REFRESH=$(printf '%s' "$DRIVER_AUTH" | jq -er '.refreshToken')
unset OTP_CODE DRIVER_AUTH

curl --fail-with-body "$API_BASE/api/v1/me" \
  -H "Authorization: Bearer $DRIVER_TOKEN"
```

Verify the DRIVER role. The `id` returned by `/me` is the **user UUID**, not the driver profile UUID above.
Respect OTP retry limits. Track token lifetime from `expiresIn`; refresh using `/api/v1/auth/refresh`
with a refresh token and Idempotency-Key, replacing both tokens on success. Reuse that key/body on an
uncertain retry. Never share tokens with the passenger tester.

## 3. Check shifts and understand availability

```bash
curl --fail-with-body "$API_BASE/api/v1/driver/shifts" \
  -H "Authorization: Bearer $DRIVER_TOKEN"
curl --fail-with-body "$API_BASE/api/v1/drivers/me/availability" \
  -H "Authorization: Bearer $DRIVER_TOKEN"
```

A shift is a future working interval. Live `AVAILABLE` is a separate Redis state. GPS presence/freshness is
separate again. **The current scheduled automatic selector uses approval, vehicle class, shifts and trip
commitments; it does not filter candidates by the live AVAILABLE toggle.** Setting UNAVAILABLE alone does
not cancel a shift, remove assignments or stop future scheduled matching. Use the dispatch/shift workflow.

If testing the live availability endpoint, use the latest `version` from GET:

```bash
AVAILABILITY_VERSION=0  # Replace with the actual version returned by GET.
AVAILABILITY_KEY=$(uuidgen)
curl --fail-with-body -X PUT "$API_BASE/api/v1/drivers/me/availability" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H "Idempotency-Key: $AVAILABILITY_KEY" \
  -H 'Content-Type: application/json' \
  --data "{\"status\":\"AVAILABLE\",\"expectedVersion\":$AVAILABILITY_VERSION}"
```

After 409, GET the latest state to reconcile. After a timeout, retry the same key/body before treating it as
a new operation. Availability requires approved readiness, but it does not reserve a trip.
The fixtures already include shifts; do not POST duplicate overlapping shifts. If adding other shifts,
use `POST /api/v1/driver/shifts` with UTC `startsAt`/`endsAt`. Active overlapping shifts are rejected.

## 4. Find your assigned trip

Ask the passenger tester to create one **SCHEDULED / QUOTED / CAR / CASH** ride inside fixture hours.
Then read:

```bash
curl --fail-with-body "$API_BASE/api/v1/driver/trips" \
  -H "Authorization: Bearer $DRIVER_TOKEN"
```

This returns upcoming **primary** assignments, each with a nested `trip` and ride details.
The normal upcoming list starts from one hour before the server's current time; an old active trip can
require its known ID and the detail endpoint. Read the trip's pickup, destination, notes and authoritative
fare before confirming.

Either fixture driver may be selected; do not assume Candidate 1 always wins. If this account has no trip,
check Candidate 2 and the passenger's booking status. The increment does not add driver offer/push delivery;
refresh this endpoint sensibly instead of assuming a notification will arrive.

```bash
TRIP_ID=REPLACE_WITH_ASSIGNED_TRIP_UUID
curl --fail-with-body "$API_BASE/api/v1/driver/trips/$TRIP_ID" \
  -H "Authorization: Bearer $DRIVER_TOKEN"
```

A backup assignment is not listed by this primary-trip endpoint. For an airport/corporate test, get the
backup assignment's trip ID from the dispatcher. A backup may confirm via the dispatch confirmation
endpoint, but cannot execute the primary driver's trip lifecycle until promoted by dispatch.

## 5. Confirm or decline

```bash
CONFIRM_KEY=$(uuidgen)
curl --fail-with-body "$API_BASE/api/v1/driver/trips/$TRIP_ID/confirm" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H "Idempotency-Key: $CONFIRM_KEY" \
  -H 'Content-Type: application/json' --data '{}'
```

Confirmation records acceptance. The trip remains `ASSIGNED`; there is no `CONFIRMED` trip status.
The driver app should make acceptance explicit before presenting the next workflow action. Do not claim
that confirmation itself starts the trip or opens passenger live tracking.

If the driver cannot do the trip, choose decline instead:

```bash
DECLINE_KEY=$(uuidgen)
curl --fail-with-body "$API_BASE/api/v1/driver/trips/$TRIP_ID/decline" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H "Idempotency-Key: $DECLINE_KEY" \
  -H 'Content-Type: application/json' --data '{"reason":"Local test: driver cannot take this trip"}'
```

Primary decline removes the driver and returns the trip to the dispatcher exception queue. This increment
has no automatic decline/timeout reassignment. A backup decline removes the backup rather than the primary.
Do not continue the lifecycle after declining. For confirmation deadlines, use the authoritative assignment
response; late assignments receive the configured grace rather than always using pickup-minus-lead-time.

## 6. Perform the trip steps in order

Define a helper in the driver's test shell:

```bash
driver_step() {
  curl --fail-with-body "$API_BASE/api/v1/driver/trips/$TRIP_ID/$1" \
    -H "Authorization: Bearer $DRIVER_TOKEN" -H "Idempotency-Key: $2" \
    -H 'Content-Type: application/json' --data '{}'
}
```

Run each action only when that stage occurs in the test. Keep its key for an uncertain retry:

```bash
EN_ROUTE_KEY=$(uuidgen)
driver_step en-route "$EN_ROUTE_KEY"

# Travel toward pickup and upload GPS; passenger can now request live tracking.
ARRIVED_KEY=$(uuidgen)
driver_step arrived "$ARRIVED_KEY"

# Passenger boards.
START_KEY=$(uuidgen)
driver_step start "$START_KEY"

# Finish the simulated journey before completing.
COMPLETE_KEY=$(uuidgen)
driver_step complete "$COMPLETE_KEY"
```

State transitions:

```text
ASSIGNED → DRIVER_EN_ROUTE → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
```

Do not send all transitions simultaneously. State guards reject invalid ordering. A shared driver database
lock prevents two trips from both starting concurrently. Assignment conflict checks also reject overlapping
primary/backup reservations, including transfer time and buffer. A 409 requires reading current state;
blindly generating new operation keys is not a fix.

The `/no-show` endpoint is a separate path, allowed only after the configured wait—currently ten minutes
after both the scheduled pickup and arrival. It is not a replacement for normal completion.

## 7. Send real test GPS through the location API

Use the **plural** `/drivers/me/location` route. Trip actions use **singular** `/driver/trips`.
The server derives the driver identity from the token; do not include a client-selected driver ID.

```bash
GPS_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
curl --fail-with-body "$API_BASE/api/v1/drivers/me/location" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H 'Content-Type: application/json' \
  --data "{\"latitude\":11.5564,\"longitude\":104.9282,\"accuracyMeters\":5,\"recordedAt\":\"$GPS_AT\"}"

curl --fail-with-body "$API_BASE/api/v1/drivers/me/location" \
  -H "Authorization: Bearer $DRIVER_TOKEN"
```

Use fresh timestamps, positive accuracy and plausible incremental movement. Do not jump between distant
coordinates instantly; the backend checks for unrealistic updates. Reading location does not refresh
presence. Reusing an older timestamp does not publish a new location.

The accepted update is stored and published atomically by a Redis Lua script to
`mobility:driver-location:v1` (unless configured otherwise). location-service subscribes, validates the
message and delivers it to authorized passenger sockets. Drivers/passengers do not connect to Redis directly.
A manual Redis `PUBLISH` does not store GPS, refresh presence or update metered pricing.

Ask the passenger tester to connect after `en-route`, send another GPS sample, and verify a matching update.
Use the [messaging command guide](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/booking-flow-java-spring-guide.md) for internal Redis/RabbitMQ inspection.

## 8. Completion and cash

After `/complete`, the trip is `COMPLETED`, with history and completion events recorded. Stop the trip-specific
GPS/tracking UI; do not assume this action has recorded cash collection. For this quoted test ride,
report the actual collected amount using integer minor units in USD or KHR:

```bash
CASH_AMOUNT_MINOR=REPLACE_WITH_ACTUAL_INTEGER_AMOUNT
CASH_KEY=$(uuidgen)
curl --fail-with-body "$API_BASE/api/v1/driver/trips/$TRIP_ID/cash" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H "Idempotency-Key: $CASH_KEY" \
  -H 'Content-Type: application/json' \
  --data "{\"amountMinor\":$CASH_AMOUNT_MINOR,\"currency\":\"USD\"}"
```

The cash endpoint accepts a report for a completed CASH trip. A differing amount is flagged as a mismatch;
do not make up an amount just to remove the warning. Fare comes from the backend. A metered ride requires
its separate backend measurement/finalization flow; GPS Pub/Sub alone does not implement metered completion.

## 9. Troubleshooting and acceptance checklist

| Symptom | Check |
| --- | --- |
| No assigned trips | Passenger booking state, other fixture driver, current worker flag/code, shifts and existing commitments. |
| Only a backup reservation | Obtain trip ID from dispatcher; primary list is not a backup feed. |
| Availability 409 | GET fresh version; check approval, documents and vehicle readiness. |
| Trip endpoint 404 | Wrong account/trip or no longer assigned; do not use the passenger token. |
| Step 409 | Current state, concurrent update, another in-progress trip or timing rule. |
| No passenger location | Driver en-route/arrived/in-progress; fresh GPS, valid passenger ownership/JWT, location-service/Redis health. |
| 401 midway through testing | Refresh session and replace tokens; reconnect WebSocket with current credentials. |
| Missing confirmation / declined trip | Dispatcher intervention is still required; do not expect automatic reassignment. |

A successful joint test has one booking, one primary assignment, explicit driver confirmation, ordered trip
steps, authorized passenger GPS updates and completed trip state. Check the backend audit/history if a UI
appears stuck rather than creating another booking.

## 10. Backend references

- [DriverDispatchController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/web/DriverDispatchController.java): shifts/confirm/decline.
- [DriverTripController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/DriverTripController.java): primary trips/lifecycle/cash.
- [DriverAvailabilityController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/driver/availability/DriverAvailabilityController.java): versioned live availability.
- [AssignmentService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/assignment/AssignmentService.java): reservations, deadlines and confirmation.
- [DriverLocationController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/location-service/src/main/java/com/mobility/location/tracking/DriverLocationController.java): GPS HTTP endpoint.
- [Detailed Java flow and annotations](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/booking-flow-java-spring-guide.md).

Documentation checked against current code. Commands are examples, not executed tests of a real passenger ride.
