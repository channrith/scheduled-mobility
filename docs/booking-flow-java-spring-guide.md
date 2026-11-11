# Booking to completion: Java/Spring Boot walkthrough and local commands

Prepared 2026-11-11 from the current working tree. This documents implemented local code, not a deployment.
The automatic worker is **disabled by default**. Scheduled/recurring/airport quoted trips can be automatically
assigned when enabled; on-demand and pickup-only metered trips remain manual. Decline/timeout recovery remains
supervised by dispatchers. The earlier main merge is still unfinished; see [implementation report](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/handover-automatic-assignment.md).

## 1. The complete flow

```mermaid
sequenceDiagram
    participant P as Passenger
    participant B as core-app / BookingService
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant MQ as RabbitMQ
    participant L as RideBookedListener
    participant W as AssignmentWorker
    participant D as Driver
    participant LS as location-service
    participant Redis as Redis
    P->>B: POST /api/v1/quotes (quoted ride)
    B-->>P: Stored fare quote + expiry
    P->>B: POST /api/v1/rides + Idempotency-Key
    B->>DB: Transaction: ride + trips + outbox
    B-->>P: Booking response (assignment is asynchronous)
    R->>DB: Claim unpublished outbox rows
    R->>MQ: mobility.events / booking.ride-booked
    MQ-->>R: Publisher confirm
    R->>DB: Mark published
    MQ->>L: Deliver to dispatch.ride-booked queue
    L->>DB: Insert deduplicated assignment jobs
    L-->>MQ: Return after commit; container acknowledges
    W->>DB: Claim due job with SKIP LOCKED
    W->>DB: Select candidates; lock/revalidate/save assignment
    D->>B: Confirm / en-route / arrived / start
    D->>LS: POST /api/v1/drivers/me/location
    LS->>Redis: Lua: save latest + presence + PUBLISH
    Redis-->>LS: DriverLocationEventListener receives event
    LS-->>P: Authorized WebSocket location update
    D->>B: POST /api/v1/driver/trips/{tripId}/complete
    B->>DB: Complete trip + history + outbox
```

A **ride** is the booking agreement. A **trip** is one journey. Recurring rides create several trips.
Assignment chooses a driver for each trip; confirmation records the driver's acceptance; neither action starts
travel. A booking response does not guarantee a driver has already been assigned.

## 2. Classes and their jobs

| Stage | Class / source | Responsibility |
| --- | --- | --- |
| Options | [BookingOptionsController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/BookingOptionsController.java) | Advertise allowed combinations; the client must respect rollout gates. |
| Eligibility | [BookingAvailabilityController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/BookingAvailabilityController.java) | Check service eligibility without creating a quote/booking; not a driver-supply check. |
| Quote | [QuoteController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/pricing/web/QuoteController.java), [QuoteService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/pricing/quote/QuoteService.java) | Resolve locations, apply policy/pricing, store fare and optional priced route snapshot. |
| Booking HTTP | [RideController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/RideController.java) | Map JSON to service input, validate contract version and required idempotency key. |
| Booking rules | [BookingService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/ride/BookingService.java) | Check ownership, quote validity, combination/time/payment policy; create ride/trips and event. |
| Trip lifecycle | [TripService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/trip/TripService.java), [TripStateMachine](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/trip/TripStateMachine.java) | Guard status transitions, save history and publish domain events. |
| Event storage | [Outbox](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/shared/outbox/Outbox.java) | Insert an event in the caller's database transaction; also publish an in-process Spring event. |
| Event transport | [OutboxRelay](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/shared/outbox/OutboxRelay.java) | Claim pending rows, send persistent RabbitMQ messages, wait for publisher confirms, retry failures. |
| Queue setup | [AutomaticAssignmentConfiguration](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/automatic/AutomaticAssignmentConfiguration.java) | Declare queue, binding, retry advice and dead-letter topology. |
| Event receiver | [RideBookedListener](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/automatic/RideBookedListener.java) | Decode/validate the envelope; persist work before acknowledging delivery. |
| Durable work | [AssignmentJobs](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/automatic/AssignmentJobs.java) | Validate trip ownership through the booking module API and insert one job per trip. |
| Background worker | [AssignmentWorker](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/automatic/AssignmentWorker.java) | Claim jobs, attempt assignment, record retries/outcomes. |
| Driver selection | [AutomaticAssignmentService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/suggest/AutomaticAssignmentService.java), [GreedyAssignmentStrategy](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/suggest/GreedyAssignmentStrategy.java) | Rank approved drivers by class, shifts, commitments, estimated travel and fairness. |
| Final reservation | [AssignmentService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/assignment/AssignmentService.java), [AssignmentGuard](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/assignment/AssignmentGuard.java) | Recheck under locks, save primary/backup assignments, history, audit and deadlines. |
| Driver actions | [DriverDispatchController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/dispatch/web/DriverDispatchController.java), [DriverTripController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/DriverTripController.java) | Confirm/decline assignment and advance the trip. |
| Live GPS storage | [DriverLocationService](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/location-service/src/main/java/com/mobility/location/tracking/DriverLocationService.java), [RedisDriverLocationRepository](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/location-service/src/main/java/com/mobility/location/tracking/RedisDriverLocationRepository.java) | Validate driver updates and atomically store/publish accepted measurements. |
| GPS subscription | [LocationEventSubscriptionConfig](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/location-service/src/main/java/com/mobility/location/realtime/LocationEventSubscriptionConfig.java), [DriverLocationEventListener](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/location-service/src/main/java/com/mobility/location/realtime/DriverLocationEventListener.java) | Subscribe to the configured Redis channel, validate event JSON, route to sockets. |
| Passenger authorization | [PassengerTrackingController](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/core-app/src/main/java/com/mobility/core/booking/web/PassengerTrackingController.java), [PassengerLocationHandshakeInterceptor](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/location-service/src/main/java/com/mobility/location/realtime/PassengerLocationHandshakeInterceptor.java) | Verify ownership and active tracking status; recheck authorization during delivery. |

## 3. The important code, explained

The following are shortened excerpts. The linked files contain all validation and error handling.

### Booking transaction and outbox

```java
@Transactional
public Booked book(BookingRequest r) {
    // Validate request, quote, ownership and policy; save the ride.
    List<Trip> created = tripService.create(ride, pickups, new Actor(user.userId(), role));
    outbox.publish("booking.ride-booked", "ride", ride.getId(),
            new RideBooked(ride.getId(), type, ride.getCorporateId(),
                    created.stream().map(Trip::getId).toList(),
                    created.getFirst().getPickupAt(), ride.getCurrency(),
                    ride.getFareMinor(), payment));
    // Return the booking.
}
```

PostgreSQL commits the booking and the outbox row together. A rollback removes both. RabbitMQ is contacted
later by the relay, so a broker outage does not require holding the passenger's booking transaction open.

`Outbox.publish` uses `@Transactional(propagation = Propagation.MANDATORY)`: calling it without an existing
transaction is an error. It stores the event's Java simple class name as `event_type`.

Example RabbitMQ envelope (illustrative IDs and timestamps):

```json
{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "type": "RideBooked",
  "aggregateType": "ride",
  "aggregateId": "22222222-2222-4222-8222-222222222222",
  "occurredAt": "2026-11-11T08:00:00Z",
  "payload": {
    "rideId": "22222222-2222-4222-8222-222222222222",
    "bookingType": "SCHEDULED",
    "corporateId": null,
    "tripIds": ["33333333-3333-4333-8333-333333333333"],
    "firstPickupAt": "2026-11-12T02:00:00Z",
    "currency": "USD",
    "fareMinor": 800,
    "paymentMethod": "CASH"
  }
}
```

`type=RideBooked` describes the event payload. **`booking.ride-booked` is the RabbitMQ routing key**, not the
JSON type. Money uses integer minor units; timestamps use UTC. These sample IDs are not executable booking data.

### Consumer and durable job

```java
@RabbitListener(queues = AutomaticAssignmentConfiguration.QUEUE,
        containerFactory = "automaticAssignmentListenerFactory",
        autoStartup = "${dispatch.automatic.listener-enabled:true}")
public void receive(Message message) {
    // Decode and validate the RideBooked envelope.
    jobs.enqueue(eventId, booked.rideId(), List.copyOf(booked.tripIds()));
}
```

```sql
INSERT INTO dispatch.assignment_jobs(trip_id, event_id, next_attempt_at)
VALUES (?, ?, ?)
ON CONFLICT (trip_id) DO NOTHING;
```

RabbitMQ can deliver an event again after a crash. The primary key on `trip_id` prevents a duplicate job.
The consumer returns after `enqueue` commits; Spring's listener container then acknowledges the delivery.
Publisher confirmation and consumer acknowledgment are separate operations.

### Job claiming, selection and final assignment

```sql
SELECT trip_id, attempts
FROM dispatch.assignment_jobs
WHERE status = 'PENDING' AND next_attempt_at <= ?
ORDER BY next_attempt_at, trip_id
LIMIT 1 FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` means another worker can claim another job instead of waiting on a job already being processed.
The job transaction holds its claim while the selection/assignment work uses separate transactions.

```java
@Transactional
public Result assignAutomatically(UUID tripId, UUID primary, UUID backup, long version) {
    guard.lockTrip(tripId);
    guard.lock(primary, backup);
    // Recheck SCHEDULED, no assigned driver, expected version and shift coverage.
    return assign(tripId, primary, backup, ReasonCode.AUTO_ASSIGNED, null, version, null);
}
```

The selector uses `@Transactional(propagation = Propagation.NOT_SUPPORTED)`: it suspends the worker's
transaction and works with snapshots. `AssignmentService` opens a fresh transaction and rechecks the decision.
This prevents a cached, outdated trip state from being accepted after waiting for a driver lock.

The final save also produces `booking.trip-assigned`. Automatic history uses `SYSTEM` as actor role.
Manual assignment and backup changes use the same conflict checks. A conflicting reservation returns
**409 `dispatch.driver-overlap`**. Optimistic trip versions prevent stale edits; driver-level database locks
prevent two different trips from simultaneously taking the same driver.

Backups reserve time too. Checks include cross-midnight trips, estimated transfer time and a buffer.
An in-progress or unknown-duration commitment is conservatively unavailable for a new reservation.
This protects stored scheduling decisions; estimated route duration cannot guarantee actual traffic conditions.

### Driver confirmation, trip progress and completion

```text
SCHEDULED → ASSIGNED → DRIVER_EN_ROUTE → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
```

Confirmation sets confirmation timestamps on the assignment; **there is no separate CONFIRMED trip status**.
Decline returns the primary driver's trip to `SCHEDULED` and opens a dispatcher exception.
The deadline watcher handles missing confirmation. This increment does not automatically reassign on decline.

`TripService.driverStep` takes the shared driver schedule lock. When starting, it checks that the driver has
no other `IN_PROGRESS` trip. Completion changes state, writes history and emits `booking.trip-completed`
(and the general status-change event). Quoted fares retain the quote's value. Metered completion uses the
existing `MeterService` measurements and finalization checks; completion is not the same as collecting cash.

### Live passenger tracking

```text
DriverLocationController.update
  → DriverLocationService.update (authentication, input/jump checks)
  → RedisDriverLocationRepository.compareAndSave
  → save-driver-location.lua: latest hash + presence + optional dispatch mirror + PUBLISH
  → RedisMessageListenerContainer
  → DriverLocationEventListener.onMessage
  → DriverLocationEventRouter
  → authorized WebSocket sessions
```

The channel defaults to **`mobility:driver-location:v1`** (`LOCATION_EVENTS_CHANNEL` can override it).
Lua publishes only accepted newer updates; stale updates do not create new location events. The latest value
is stored separately from Pub/Sub delivery.

The passenger's WebSocket URL is `/api/v1/passenger/trips/{tripId}/location/live`. The handshake checks the
passenger JWT and resolves access through core-app. Tracking is permitted in `DRIVER_EN_ROUTE`,
`DRIVER_ARRIVED`, and `IN_PROGRESS`, not merely after assignment. Authorization is rechecked during delivery;
completion, cancellation, reassignment, expiration or a core access failure stops unauthorized delivery.

## 4. Who publishes and who subscribes?

| Mechanism | Publisher | Subscriber / consumer | Durability and purpose |
| --- | --- | --- | --- |
| Spring application event | `Outbox.publish` | Registered in-process Spring listeners | Same JVM; distinct from broker delivery. Transactional listener behavior depends on its annotation. |
| RabbitMQ booking event | `OutboxRelay` | `RideBookedListener`, through `dispatch.ride-booked` | Durable queue and persistent messages; drives dispatch ingestion. |
| Redis GPS event | `save-driver-location.lua` | `DriverLocationEventListener` in location-service instances | Pub/Sub fan-out; no durable replay for disconnected subscribers. |
| Passenger WebSocket | Location-service event router/session layer | Passenger client | Authorized live updates; passenger does not connect directly to Redis or RabbitMQ. |

A RabbitMQ exchange routes messages to queues using bindings. Consumers receive from queues.
Multiple consumers of one queue share work; separate bound queues can each receive a copy.
Redis Pub/Sub broadcasts to currently connected subscribers; a subscriber that is offline misses the publication.

## 5. Annotation meanings in this project

| Annotation | Meaning |
| --- | --- |
| `@RestController` | Spring-managed HTTP controller; return values are serialized as response bodies. |
| `@RequestMapping` | Shared path prefix, e.g. `/api/v1/rides`. |
| `@PostMapping`, `@GetMapping` | Bind an HTTP method/path to a Java method. |
| `@RequestBody` | Deserialize request JSON into the Java request DTO/record. |
| `@Valid`, `@NotNull` | Run Bean Validation / reject missing required values; domain rules still need service checks. |
| `@ModelAttribute` on a method | Execute controller preparation before request handlers; availability sets `Cache-Control: no-store`. |
| `@PreAuthorize` | Enforce an authorization expression before invocation when method security applies. |
| `@AuthenticationPrincipal` | Inject the authenticated JWT/principal; do not trust a driver ID supplied by the client. |
| `@Service`, `@Component`, `@Repository` | Register Spring-managed objects; the stereotypes indicate business logic, generic infrastructure or persistence. |
| `@Configuration`, `@Bean` | Define managed objects such as exchanges, queue declarations and listener containers. |
| `@ConfigurationProperties`, `@DefaultValue`, `@Validated` | Bind typed settings, supply defaults and validate them. |
| `@Value` | Inject a configured value, such as the Redis channel or geocoding URL. |
| `@Transactional` | Apply transaction interception on calls through the Spring proxy; runtime failures normally roll back. |
| `@Transactional(readOnly = true)` | Mark a read transaction; it is not authorization and does not replace domain checks. |
| `Propagation.MANDATORY` | Require a caller transaction, as used by the outbox and driver lock. |
| `Propagation.NOT_SUPPORTED` | Suspend an existing transaction, as used during automatic selection. |
| `@Scheduled` | Run a method on a schedule when scheduling is enabled; this alone is not a distributed lock. |
| `@RabbitListener` | Register a message consumer through Spring AMQP. |
| `@Entity`, `@Table`, `@Id`, `@Column` | Map Java entities and fields to persistent tables/columns. |
| `@Version` | Optimistic locking: a stale entity update conflicts instead of silently overwriting another write. |
| `@Operation`, `@ProblemResponse` | Describe the API in OpenAPI; documentation does not perform authorization or validation. |
| `@Test`, `@IntegrationTest` | JUnit test / this repository's composed annotation for Spring and Testcontainers integration tests. |

A Java `record` is a language feature, not an annotation. Constructor injection makes required dependencies
explicit. A same-object method call does not trigger a new Spring transactional proxy interception; for example,
`assignAutomatically` calls `assign` inside the already active assignment transaction.

## 6. Local setup and HTTP examples

Run from the repository root. The commands below are examples for your local development environment;
this document does not execute them or create bookings. Use existing test accounts/tokens and configured
coverage/pricing. Gateway defaults to port 8090, core-app to 8080, location-service to 8081.

```bash
cd /Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility
set -a
source .env
set +a
docker compose up -d --wait postgres redis rabbitmq
scripts/dev.sh up
```

To enable automatic assignment on the local processes when they next start:

```bash
export DISPATCH_AUTOMATIC_ENABLED=true
# Restart core-app using your normal local runner to pick up the setting.
```

The driver needs an approved account, a vehicle of the right class and shift coverage. Airport/corporate
trips also require a second eligible driver. Keep existing booking rollout flags unchanged.

Use separate passenger and driver tokens. Set `PASSENGER_TOKEN`, `DRIVER_TOKEN`, `TRIP_ID`, `QUOTE_ID`,
`PICKUP_AT` and `BOOKING_KEY` locally; `PICKUP_AT` must be an eligible future UTC timestamp.
For uncertain retries, reuse the same idempotency key and identical request body.

```bash
API_BASE=http://localhost:8090
curl --fail-with-body "$API_BASE/api/v1/booking/options" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" -H 'Accept-Language: en'
```

Create a quote using approved test pickup/drop-off coordinates or landmark UUIDs in a local `quote-request.json`:

```json
{
  "pickup": {"latitude": 11.5564, "longitude": 104.9282},
  "dropoff": {"latitude": 11.5700, "longitude": 104.9230},
  "vehicleClass": "CAR",
  "pickupAt": "REPLACE_WITH_ELIGIBLE_FUTURE_UTC_TIME",
  "fareModel": "QUOTED"
}
```

These coordinates are illustrative, not a promise that your development zones/pricing accept them.

```bash
curl --fail-with-body "$API_BASE/api/v1/quotes" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept-Language: en' \
  --data-binary @quote-request.json

# Fill QUOTE_ID from the successful response and use a unique BOOKING_KEY for a new booking.
curl --fail-with-body "$API_BASE/api/v1/rides" \
  -H "Authorization: Bearer $PASSENGER_TOKEN" \
  -H "Idempotency-Key: $BOOKING_KEY" -H 'Content-Type: application/json' \
  --data "{\"quoteId\":\"$QUOTE_ID\",\"type\":\"SCHEDULED\",\"fareModel\":\"QUOTED\",\"paymentMethod\":\"CASH\",\"contractVersion\":2}"
```

Quoted booking uses route/time/class from the quote. Do not send direct destination overrides in the booking.
Record the returned trip ID; assignment happens asynchronously.

Driver lifecycle commands (send each at the appropriate real test stage, with a distinct key per action):

```bash
curl --fail-with-body -X POST "$API_BASE/api/v1/driver/trips/$TRIP_ID/confirm" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H "Idempotency-Key: $TRIP_ID-confirm" \
  -H 'Content-Type: application/json' --data '{}'

# Repeat the same HTTP shape for /en-route, /arrived, /start, /complete in that order.
# Use a different Idempotency-Key suffix for each action; confirmation does not perform these steps.

curl --fail-with-body "$API_BASE/api/v1/passenger/trips/$TRIP_ID/tracking-access" \
  -H "Authorization: Bearer $PASSENGER_TOKEN"
```

The tracking-access response authorizes the connection; it does not contain GPS coordinates. Use an
authenticated WebSocket client for `ws://localhost:8090/api/v1/passenger/trips/$TRIP_ID/location/live`.
A browser's native WebSocket API cannot set an arbitrary Authorization header; follow the client's supported
authentication integration rather than putting tokens in query strings.

A local driver GPS update through the actual application path:

```bash
GPS_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
curl --fail-with-body "$API_BASE/api/v1/drivers/me/location" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -H 'Content-Type: application/json' \
  --data "{\"latitude\":11.5564,\"longitude\":104.9282,\"accuracyMeters\":5,\"recordedAt\":\"$GPS_AT\"}"
```

Use plausible successive measurements; the service rejects stale/invalid jumps. This GPS endpoint is separate
from the existing metered-measurement flow; publishing a Redis message alone does not update a fare.

## 7. PostgreSQL: inspect the booking, outbox and jobs

Open a local SQL session using the container's configured credentials (no password output):

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Then run these read-only queries:

```sql
SELECT id, ride_id, status, driver_id, pickup_at, version
FROM booking.trips ORDER BY pickup_at DESC LIMIT 20;

SELECT id, event_type, routing_key, aggregate_type, aggregate_id,
       occurred_at, published_at, attempts, next_attempt_at, last_error
FROM outbox.outbox_events ORDER BY seq DESC LIMIT 30;

SELECT id, payload
FROM outbox.outbox_events
WHERE routing_key = 'booking.ride-booked'
ORDER BY seq DESC LIMIT 5;

SELECT trip_id, event_id, status, attempts, next_attempt_at, last_reason
FROM dispatch.assignment_jobs ORDER BY updated_at DESC LIMIT 30;

SELECT trip_id, primary_driver_id, backup_driver_id, primary_confirmed_at,
       primary_deadline, reason_code, version
FROM dispatch.trip_assignments ORDER BY updated_at DESC LIMIT 20;

SELECT trip_id, action, role, driver_id, reason_code, occurred_at
FROM dispatch.assignment_history ORDER BY seq DESC LIMIT 30;
```

`published_at IS NULL` means relay delivery is still pending. A published event can still have a pending
assignment job: broker delivery and business processing are different stages. Do not manually set
`published_at` or delete job rows to simulate success.

## 8. RabbitMQ: inspect transport and test isolated publish/consume

Read-only broker inspection:

```bash
docker compose exec rabbitmq rabbitmq-diagnostics -q ping
docker compose exec rabbitmq rabbitmqctl list_exchanges -p / name type durable
docker compose exec rabbitmq rabbitmqctl list_bindings -p / source_name destination_name routing_key
docker compose exec rabbitmq rabbitmqctl list_queues -p / \
  name durable messages_ready messages_unacknowledged consumers
docker compose exec rabbitmq rabbitmqctl list_consumers -p /
```

Look for exchange `mobility.events`, queue `dispatch.ride-booked`, routing key `booking.ride-booked`,
and DLQ `dispatch.ride-booked.dead`. `messages_ready` waits for delivery;
`messages_unacknowledged` has been delivered but not yet acknowledged.

Management UI defaults to `http://localhost:15672`, with credentials from your local `.env`.
The following HTTP API commands demonstrate an **isolated debug queue**, not a real booking event.
They mutate only the named debug exchange, queue and binding on your local broker. They use the default `/` vhost (`%2F` in URLs).
The publish example uses POST, matching the repository's RabbitMQ 3-management image and
[RabbitMQ 3.13 implementation](https://github.com/rabbitmq/rabbitmq-server/blob/v3.13.7/deps/rabbitmq_management/src/rabbit_mgmt_wm_exchange_publish.erl).
Queue declaration and consumption payloads are described in the
[official HTTP API reference](https://www.rabbitmq.com/docs/http-api-reference).

```bash
RABBIT_ADMIN_URL="http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}"

# Declare an isolated demo exchange.
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  -X PUT "$RABBIT_ADMIN_URL/api/exchanges/%2F/mobility.debug.booking-guide" \
  -H 'Content-Type: application/json' \
  --data '{"type":"direct","durable":false,"auto_delete":false,"internal":false,"arguments":{}}'

# Declare the demo queue.
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  -X PUT "$RABBIT_ADMIN_URL/api/queues/%2F/mobility.debug.booking-guide" \
  -H 'Content-Type: application/json' \
  --data '{"durable":false,"auto_delete":false,"arguments":{}}'

# Bind the demo queue to the demo exchange.
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  "$RABBIT_ADMIN_URL/api/bindings/%2F/e/mobility.debug.booking-guide/q/mobility.debug.booking-guide" \
  -H 'Content-Type: application/json' \
  --data '{"routing_key":"mobility.debug.booking-guide","arguments":{}}'

# Publish through the demo exchange (POST, selected by curl when --data is supplied).
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  "$RABBIT_ADMIN_URL/api/exchanges/%2F/mobility.debug.booking-guide/publish" \
  -H 'Content-Type: application/json' \
  --data '{"properties":{},"routing_key":"mobility.debug.booking-guide","payload":"hello dispatch","payload_encoding":"string"}'

# Fetch and acknowledge one DEMO message. This consumes it from the demo queue.
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  "$RABBIT_ADMIN_URL/api/queues/%2F/mobility.debug.booking-guide/get" \
  -H 'Content-Type: application/json' \
  --data '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto","truncate":1000}'

# Remove only these demo resources when finished.
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  -X DELETE "$RABBIT_ADMIN_URL/api/queues/%2F/mobility.debug.booking-guide"
curl --fail-with-body --user "${RABBITMQ_USERNAME:-mobility}:${RABBITMQ_PASSWORD:-mobility}" \
  -X DELETE "$RABBIT_ADMIN_URL/api/exchanges/%2F/mobility.debug.booking-guide"
```

For the real path, create a valid local booking and let `OutboxRelay` publish it. An invented trip ID sent
manually to the real queue fails validation. Getting messages from a production queue can consume or reorder
work; use the read-only counts above when inspecting real dispatch.

## 9. Redis: subscribe, publish and inspect latest location

Redis uses the password in `.env`. Passing `REDISCLI_AUTH` avoids printing it in Redis CLI output.
Each subscription command stays open until Ctrl+C. Use separate terminals.

```bash
# Terminal A: observe actual accepted driver updates (local/test environment).
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli SUBSCRIBE "${LOCATION_EVENTS_CHANNEL:-mobility:driver-location:v1}"
```

Submit the driver GPS HTTP request from section 6 in another terminal. Expect an event shaped like:

```json
{
  "schemaVersion": 1,
  "type": "driver.location.updated",
  "eventId": "11111111-1111-4111-8111-111111111111",
  "driverId": "44444444-4444-4444-8444-444444444444",
  "latitude": 11.5564,
  "longitude": 104.9282,
  "accuracyMeters": 5,
  "recordedAt": "2026-11-11T08:00:00Z",
  "receivedAt": "2026-11-11T08:00:01Z"
}
```

Demonstrate `SUBSCRIBE`/`PUBLISH` without injecting fake GPS into application sockets:

```bash
# Terminal A
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli SUBSCRIBE mobility:debug:booking-guide

# Terminal B
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli PUBLISH mobility:debug:booking-guide '{"message":"hello passenger tracking"}'
```

The integer returned by `PUBLISH` counts subscribed clients that receive the publication; it does not mean a
passenger rendered the update. `PUBLISH` alone does **not** write the location hash or refresh presence.

Inspect the real stored state with IDs from your test accounts:

```bash
# DRIVER_ID is the driver profile UUID; DRIVER_USER_ID is its login UUID. They are different IDs.
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli HGETALL "location:driver:{$DRIVER_ID}:latest"
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli TTL "location:driver:{$DRIVER_ID}:latest"
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli GET "location:driver:{$DRIVER_ID}:presence"
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli HGETALL "driver:location:$DRIVER_USER_ID"
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli GEOPOS driver:positions "$DRIVER_USER_ID"
docker compose exec -e REDISCLI_AUTH="${REDIS_PASSWORD:-mobility}" redis \
  redis-cli PUBSUB NUMSUB "${LOCATION_EVENTS_CHANNEL:-mobility:driver-location:v1}"
```

`latest` defaults to 24-hour retention; separate presence defaults to 60 seconds. Read operations do not
refresh either. The dispatch mirror hash uses `lat`, `lng`, `recordedAt` epoch milliseconds; the primary
location hash uses `latitude`, `longitude`, ISO timestamps. Old GEO members can remain after hash expiry;
GEO membership alone is not proof a driver is online or available.

## 10. Failure diagnosis

| Symptom | Check |
| --- | --- |
| Booking rejected | Authentication, combination from booking/options, quote ownership/expiry, supported payment/time/zone. |
| Booked but no job | Outbox `published_at`/`last_error`, RabbitMQ binding/consumer, dead-letter queue. |
| Job pending but no assignment | Worker enabled, `next_attempt_at`, horizon, `last_reason`, driver class/shifts/commitments. |
| `MANUAL` job | Missing supply/backup, passed pickup, unsupported live-dispatch mode or exhausted retries; review dispatcher exception. |
| 409 on assigning a driver | Stale trip version or overlapping primary/backup reservation; reload current state. |
| Assigned but no passenger GPS | Driver must progress to an allowed tracking state, upload fresh GPS, have valid ownership/access and an authenticated socket. |
| Redis publication but no socket update | Event validation, driver/profile identity, subscriber connection and per-delivery authorization. |
| Completion refused on metered ride | Measurement coverage/finalization checks; do not bypass by inventing a fare. |

Default job settings: five-second poll, batch 20, one-minute retry delay, 60 attempts, seven-day horizon.
Outside-horizon deferral does not consume an attempt. Queue ingestion retries three times after the initial
attempt with 1/2/4-second waits, then dead-letters. Review and replay failed real messages only after resolving
their cause; do not use the isolated demo payload for application dispatch.

## 11. Tests and cleanup scope

Targeted cleanup changes only these five files: `BookingAvailabilityController`, `GeocodingProvider`,
`ServiceEligibilityService`, `MeterService`, `MeterSession`. It expands statements, adds braces, replaces
wildcard imports and wraps expressions. No endpoint, validation rule, database mapping, monetary calculation
or transaction boundary is intentionally changed. Other review findings are outside this targeted cleanup.

Run the focused behavior/API checks:

```bash
./mvnw -pl core-app -am verify \
  -Dtest=BookingAvailabilityControllerTest,GeocodingProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=BookingModesIT,PassengerLocationIT,ReversePlacesIT,OpenApiSnapshotIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Run the booking/assignment flow checks:

```bash
./mvnw -pl core-app -am verify \
  -Dit.test=AutomaticAssignmentIT,AssignmentIT,BookingFlowIT,OutboxIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Tests use disposable infrastructure via Testcontainers. These commands do not execute the manual booking,
Redis or RabbitMQ demos above. The previous automatic-assignment full verification passed; see its linked
report for exact counts and scope. Current cleanup verification: **BUILD SUCCESS** on 2026-11-11. Seven unit tests and twelve
integration tests were reported, with one integration test skipped and no failures/errors.
The OpenAPI snapshot matched without regeneration. `git diff --check` and
`scripts/check.sh --no-console --no-backend` also passed. Log: `/tmp/booking-cleanup-tests.log`.
Manual HTTP booking, Redis publication and RabbitMQ demo commands were reviewed but not executed.
No production booking, commit, push or deployment was performed.

## Role-specific setup guides

- [Passenger setup and booking test](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/passenger-local-setup.md)
- [Driver setup and trip execution test](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/driver-local-setup.md)
