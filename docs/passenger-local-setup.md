# Passenger setup and booking test guide

Local setup verified 2026-11-11. This guide describes the existing API flow; it does not claim a passenger UI
has been implemented. Commands create local test data only when you run them.

## 1. What you need

- Local PostgreSQL, Redis, RabbitMQ, core-app, location-service and api-gateway running.
- Use the gateway: `http://localhost:8090`. Native/mobile clients use the machine's reachable address instead
  of their own device's `localhost`; account for simulator/emulator networking in your client configuration.
- The current core-app code must be running with the local profile and `DISPATCH_AUTOMATIC_ENABLED=true`
  for automatic assignment. Restart the IDE process after changing its environment or source.
- Seed fixtures if needed: `./scripts/seed-booking.sh` from the repository root. This preserves existing
  pricing and adds missing fixture coverage. It does not create a booking or enable the worker.
- Passenger login: **`010555803`** (`+85510555803`), name **Local Booking Passenger**.
- Test driver candidates: `010555801` and `010555802`; both have approved CAR vehicles and documents.
- Seeded shifts are **November 12–18, 2026, 06:00–20:00 Asia/Phnom_Penh**. Rerun the seed for later dates.
  The full trip must fit in a driver's shift. These are test fixtures, not real transport services.

[Seed details](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/local-booking-seed.md) · [Driver setup](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/driver-local-setup.md)

## 2. Sign in as the passenger

The following examples use Bash, curl and jq. Use a separate terminal/Postman environment for each role.
No test password is created. Use the OTP configured/delivered by your existing local authentication setup;
the seed does not set a new OTP code.

```bash
API_BASE=http://localhost:8090
PASSENGER_PHONE=010555803
curl --fail-with-body "$API_BASE/api/v1/auth/otp/request" \
  -H 'Content-Type: application/json' -H 'Accept-Language: en' \
  --data "{\"phone\":\"$PASSENGER_PHONE\"}"

# Enter the current local OTP when prompted by this shell read; input is hidden.
read -r -s OTP_CODE
printf '\n'
PASSENGER_AUTH=$(curl --fail-with-body "$API_BASE/api/v1/auth/otp/verify" \
  -H 'Content-Type: application/json' \
  --data "{\"phone\":\"$PASSENGER_PHONE\",\"code\":\"$OTP_CODE\"}")
PASSENGER_TOKEN=$(printf '%s' "$PASSENGER_AUTH" | jq -er '.accessToken')
PASSENGER_REFRESH=$(printf '%s' "$PASSENGER_AUTH" | jq -er '.refreshToken')
unset OTP_CODE PASSENGER_AUTH

curl --fail-with-body "$API_BASE/api/v1/me" \
  -H "Authorization: Bearer $PASSENGER_TOKEN"
```

Verify `roles` includes `PASSENGER`. A 202 OTP request response means the request was accepted, not that you
are logged in. Respect `Retry-After` for OTP throttling. Use the response's `expiresIn` to track token expiry.
Refresh tokens rotate: call `POST /api/v1/auth/refresh` with `{"refreshToken":"..."}`, a new operation's
Idempotency-Key, and replace both stored tokens. Retry an uncertain refresh using the same key/body.
Never put bearer tokens in WebSocket query strings or documentation/screenshots.

## 3. Read the offered booking combinations

```bash
curl --fail-with-body "$API_BASE/api/v1/booking/options" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" -H 'Accept-Language: en'
```

Use `combinations`, not just `modes`, as the authority. This walkthrough uses **SCHEDULED + QUOTED + CAR + CASH**.
On-demand and metered rollout settings are independent; the scheduled automatic planner does not handle them.
The optional `POST /api/v1/booking/availability` preflight checks service eligibility, not driver supply,
reservation or a guaranteed fare. Quote creation and booking still revalidate.

## 4. Create a quote

Choose a future time within seeded shifts and the `minimumScheduleNoticeSeconds`/`maximumScheduleDays`
returned by booking options. For example, **2026-11-12 09:00 Phnom Penh = `2026-11-12T02:00:00Z`**.
Change this when it is no longer in the future. The coordinates below lie within the demo city rectangle;
actual policy/pricing responses remain authoritative.

```bash
PICKUP_AT=2026-11-12T02:00:00Z
QUOTE_RESPONSE=$(curl --fail-with-body "$API_BASE/api/v1/quotes" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept-Language: en' \
  --data "{\"pickup\":{\"latitude\":11.5564,\"longitude\":104.9282},\"dropoff\":{\"latitude\":11.5700,\"longitude\":104.9230},\"vehicleClass\":\"CAR\",\"pickupAt\":\"$PICKUP_AT\",\"fareModel\":\"QUOTED\"}")
printf '%s\n' "$QUOTE_RESPONSE" | jq .
QUOTE_ID=$(printf '%s' "$QUOTE_RESPONSE" | jq -er '.id')
```

Display `totalMinor`, `currency`, `expiresAt`, pickup/drop-off and scheduled time. Money uses integer minor
units: USD 800 means $8.00; do not apply USD cent scaling to KHR. `route` can be null. Do not draw a fabricated
priced route when geometry is unavailable. Use stored geometry when supplied.

## 5. Book using the quote

```bash
BOOKING_KEY=$(uuidgen)
BOOKING_RESPONSE=$(curl --fail-with-body "$API_BASE/api/v1/rides" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" \
  -H "Idempotency-Key: $BOOKING_KEY" \
  -H 'Content-Type: application/json' -H 'Accept-Language: en' \
  --data "{\"quoteId\":\"$QUOTE_ID\",\"type\":\"SCHEDULED\",\"fareModel\":\"QUOTED\",\"paymentMethod\":\"CASH\",\"contractVersion\":2,\"pickupNotes\":\"Local test: meet at the main entrance\"}")
printf '%s\n' "$BOOKING_RESPONSE" | jq .
RIDE_ID=$(printf '%s' "$BOOKING_RESPONSE" | jq -er '.id')
TRIP_ID=$(printf '%s' "$BOOKING_RESPONSE" | jq -er '.trips[0].id')
```

Reuse the same `BOOKING_KEY` and identical body after an uncertain network result. Do not generate another
key just because the response timed out. The quoted booking takes location/time/class from the quote;
do not add a direct `dropoff` override. Save both IDs: a ride is the booking, a trip is one journey.
Recurring rides have multiple trip IDs; selecting only `trips[0]` is appropriate for this one-trip example.

## 6. Wait for assignment and follow progress

```bash
curl --fail-with-body "$API_BASE/api/v1/rides/$RIDE_ID" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" -H 'Accept-Language: en'
```

Read `trips` in the response. Refresh this read at a modest interval in a test client; no new passenger
trip-status push channel is implemented by the assignment increment. Do not poll at GPS frequency.

| Trip state | Passenger UI meaning / action |
| --- | --- |
| `SCHEDULED` | Booking saved, awaiting driver assignment. Do not show a confirmed driver yet. |
| `ASSIGNED` | Driver assigned. Assignment and driver confirmation are distinct; this status alone does not prove acceptance. |
| `DRIVER_EN_ROUTE` | Driver is on the way; live tracking access can now be requested. |
| `DRIVER_ARRIVED` | Driver has arrived; passenger prepares to board. |
| `IN_PROGRESS` | Ride underway. |
| `COMPLETED` | Journey finished; stop active tracking. Cash reporting is a separate driver action. |
| Cancellation / `NO_SHOW` | Show terminal status and stop tracking/retry prompts as appropriate. |

Behind this read: booking transaction → outbox → RabbitMQ queue → durable job → candidate selection →
locked assignment. Neither successful booking HTTP response nor RabbitMQ delivery means the driver accepted.
A no-driver result remains pending for retries or manual dispatcher action; do not keep rebooking it.

## 7. Live driver tracking

```bash
curl --fail-with-body "$API_BASE/api/v1/passenger/trips/$TRIP_ID/tracking-access" \
  -H "Authorization: Bearer $PASSENGER_TOKEN"
```

This authorizes access and returns trip/driver IDs, not GPS. It is expected to reject tracking while the
trip is only `SCHEDULED` or `ASSIGNED`. The signed-in passenger must be the rider or original booker.

For local testing, open a **Postman WebSocket request**:

- URL: `ws://localhost:8090/api/v1/passenger/trips/<TRIP_ID>/location/live`
- Handshake header: `Authorization: Bearer <PASSENGER_TOKEN>`
- Connect after the driver has marked en route. Use `wss://` when testing a TLS endpoint.
- Ask the driver tester to upload fresh GPS through the driver endpoint in the driver guide.
- Expect `driver.location.snapshot` when a stored location is available, followed by
  `driver.location.updated` events for accepted new positions. A snapshot can be stale: inspect `fresh`
  and `ageSeconds`. Keep timestamp order and handle reconnects.
- Clients receive messages; this socket does not accept client GPS/control messages.

The browser's built-in WebSocket constructor cannot set this arbitrary bearer header. A browser client needs
an explicitly supported authenticated integration; this guide does not invent a query-token workaround.
Redis Pub/Sub is internal to location-service. Passenger clients subscribe to the WebSocket, not Redis.
Authorization is checked during delivery; completion/cancellation/reassignment/expired authentication can
stop delivery. A reconnect can obtain the latest stored snapshot; it does not replay every missed GPS event.

## 8. Cancel a test booking when needed

```bash
CANCEL_KEY=$(uuidgen)
curl --fail-with-body "$API_BASE/api/v1/rides/$RIDE_ID/cancel" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" -H "Idempotency-Key: $CANCEL_KEY" \
  -H 'Content-Type: application/json' \
  --data "{\"tripId\":\"$TRIP_ID\",\"reason\":\"Local test cancelled by passenger\"}"
```

Passenger cancellation is subject to the state machine; do not expect cancellation after the driver has
arrived. Specifying `tripId` targets one trip; omission can affect the remaining cancellable ride series.
Re-read the ride after a successful cancellation and close tracking.

## 9. Joint test checklist and troubleshooting

1. Driver tester signs in to both fixture accounts and checks shifts.
2. Passenger creates one scheduled CAR quote and booking inside those shifts.
3. Passenger shares `TRIP_ID`/`RIDE_ID` with the testers, never the bearer token.
4. The driver who actually receives the primary assignment confirms it, then marks en route.
5. Passenger connects tracking; driver sends fresh GPS.
6. Driver marks arrived → start → complete as those events occur in the simulation.
7. Passenger sees `COMPLETED`, stops tracking, and uses the stored quoted fare for the cash test.

| Symptom | Next check |
| --- | --- |
| 401 | Refresh/login and use the passenger token, not the driver's. |
| 403 / unavailable action | Correct role and booking ownership; inspect Problem Details `code`. |
| Quote rejected | Future time, permitted combination, active coverage/pricing, provider health and quote ownership. |
| Booking stays scheduled | Current core-app restarted, worker flag on, shifts cover trip, drivers free, required backup available. |
| 409 on tracking | Driver has not reached a trackable state, or trip ended. |
| WebSocket connects but no updates | Driver uploads fresh GPS; inspect snapshot freshness, access and location-service health. |
| Expired quote | Obtain a new quote and ask the passenger to review the current fare before a new booking attempt. |

## 10. Backend references

- [AuthController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/identity/auth/AuthController.java): OTP/token contract.
- [RideController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/RideController.java): booking/read/cancel API.
- [BookingService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/ride/BookingService.java): booking policy/transaction.
- [PassengerTrackingController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/PassengerTrackingController.java): ownership and state checks.
- [Full class/annotation/outbox/RabbitMQ/Redis guide](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/booking-flow-java-spring-guide.md).

Documentation checked against current code. These examples were not executed and do not create an additional booking.
