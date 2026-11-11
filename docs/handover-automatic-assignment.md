# Automatic assignment and the booking flow

This is local implementation work, not a deployment. Automatic execution is off by default.

## Beginner walkthrough

1. The passenger submits the existing booking request. `BookingService.book` validates ownership,
   booking policy, quote expiry, required locations and payment rules. It saves the ride, its trips,
   and an outbox row within the booking transaction. A recurring ride has several trip IDs.
2. `OutboxRelay.relayBatch` publishes committed outbox rows to the durable `mobility.events`
   exchange. The routing key is `booking.ride-booked`; the JSON envelope's `type` is **`RideBooked`**.
   These are different fields. Publisher confirmation means RabbitMQ accepted the message,
   not that dispatch assigned a driver or that the driver accepted.
3. RabbitMQ routes the event to `dispatch.ride-booked`. `RideBookedListener.receive` validates
   the envelope and calls `AssignmentJobs.enqueue`. That transaction checks each trip against
   booking's public `Trips` API and inserts one durable job per trip. `ON CONFLICT DO NOTHING`
   makes repeated delivery harmless. The listener returns only after the transaction commits.
4. `AssignmentWorker.poll` claims due jobs with PostgreSQL `FOR UPDATE SKIP LOCKED`. Other
   application instances skip an already claimed job. A disabled worker still allows ingestion.
5. `AutomaticAssignmentService.attempt` selects candidates using the existing greedy strategy.
   Selection reads detached snapshots. It considers approved drivers, vehicle class, shift coverage,
   primary **and backup** commitments across dates, estimated travel between trips, and a buffer.
   Airport/corporate trips need a distinct eligible backup. Scheduled, airport and recurring quoted
   trips are supported. On-demand or pickup-only trips enter manual dispatch with
   `LIVE_DISPATCH_REQUIRED`; this scheduled planner does not invent a metered trip duration.
6. `AssignmentService.assignAutomatically` opens a fresh transaction. It locks the trip, then driver
   schedules in sorted UUID order, rechecks state/version/shifts and conflicts, and invokes the
   existing assignment logic. The transaction records the assignment, audit/history,
   confirmation deadline, booking status transition and `booking.trip-assigned` outbox event.
   Automatic booking history uses actor role `SYSTEM`, with a null human actor ID.
7. The driver calls `POST /api/v1/driver/trips/{tripId}/confirm` or `/decline`.
   Confirmation records acceptance; it does not start the trip. Decline removes the driver's
   assignment and opens the existing dispatcher exception. Timeout also uses the existing
   deadline watcher. Automatic reassignment after decline/timeout is not enabled by this increment.
8. The existing driver endpoints move the trip through `/en-route`, `/arrived`, `/start`, `/complete`.
   Driver transitions share the driver schedule lock. Two concurrent starts for the same driver
   cannot both pass the existing “already in progress” check.
9. Live location delivery remains the existing location-service path: authenticated driver updates
   are stored/published through Redis and the passenger connects to
   `/api/v1/passenger/trips/{tripId}/location/live` after the existing ownership/access checks.
   This increment does not replace tracking or add RabbitMQ GPS messages.

## Core code examples

The consumer persists work before RabbitMQ acknowledges it:

```java
@RabbitListener(queues = AutomaticAssignmentConfiguration.QUEUE,
        containerFactory = "automaticAssignmentListenerFactory",
        autoStartup = "${dispatch.automatic.listener-enabled:true}")
public void receive(Message message) {
    // Validate envelope/type/aggregate and decode RideBooked.
    jobs.enqueue(eventId, booked.rideId(), List.copyOf(booked.tripIds()));
}
```

The final assignment uses fresh database state under locks:

```java
@Transactional
public Result assignAutomatically(UUID tripId, UUID primary, UUID backup, long version) {
    guard.lockTrip(tripId);
    guard.lock(primary, backup);
    // Recheck SCHEDULED, expected version, shift coverage, and both drivers' commitments.
    return assign(tripId, primary, backup, ReasonCode.AUTO_ASSIGNED, null, version, null);
}
```

These snippets omit checks for readability; see the actual methods for executable code.

## Concurrency rules and behavior changes

- Manual assignment, backup changes and backup switches use the same schedule guard as automation.
  A conflicting reservation now returns **409 `dispatch.driver-overlap`**, instead of merely
  returning an overlap warning. Missing shifts remain warnings for manual assignment; automation
  requires coverage. Endpoint paths and request shapes are unchanged.
- Trip locks serialize dispatch mutations; optimistic versions still reject stale requests.
  Driver locks prevent different trip requests from racing to reserve the same driver.
  Locks are PostgreSQL transaction locks and work across JVMs, not just within one process.
- Backups reserve time too. A driver cannot back up one overlapping trip and drive another.
- All open commitments are considered, including those crossing midnight. There is no daily
  2,000-trip query cap in the automatic conflict check.
- An in-progress trip blocks new reservations. Unknown destination/release time conservatively
  blocks another reservation until the trip closes. The known-duration schedule includes the
  configured travel estimate and buffer; it cannot guarantee real traffic or actual arrival times.
- Shift cancellation uses the same existing per-driver lock namespace. A cancellation racing an
  assignment either commits before final coverage validation or observes the new reservation.
- Selection intentionally runs outside the assignment transaction. Its result can become stale;
  the fresh locked assignment transaction is the authority. A conflict retries through the job queue.
- The worker holds a job transaction while selection and assignment use independent transactions.
  Budget at least two database connections per concurrent worker plus request headroom. A crash
  after assignment commit but before job completion is safe: the next attempt sees the assigned trip.
- Existing overlapping records are not rewritten. Existing decline/timeout procedures remain
  dispatcher-supervised, so a delayed original booking event does not resurrect completed work.

## Failure and rollout behavior

Migration: `V19__automatic_assignment.sql` creates `dispatch.assignment_jobs`; no existing booking
columns are changed. States are `PENDING`, `DONE`, `MANUAL`. Keep completed rows for deduplication.

Configuration in `application.yml`:

```yaml
dispatch:
  automatic:
    enabled: false           # DISPATCH_AUTOMATIC_ENABLED=true enables polling
    listener-enabled: true  # persist incoming bookings even before worker rollout
    poll-interval: 5s
    retry-delay: 1m
    max-attempts: 60
    horizon: 7d
    batch-size: 20
```

Future trips wait until the seven-day horizon without consuming an attempt. No eligible driver
creates a visible `UNASSIGNED` exception and schedules another attempt. Exhaustion or a passed
pickup time leaves the trip for manual dispatch. Technical assignment failures roll back separately
from retry bookkeeping; exhausted failures also create an exception.

The listener makes an initial attempt plus three bounded retries (1/2/4-second waits). Invalid
messages and exhausted ingestion failures go to durable `dispatch.ride-booked.dead` via
`dispatch.dead`. Monitor and replay this queue after fixing the cause. No new external service
or dependency is required. Broker durability and DLQ operations still need normal infrastructure
monitoring and backups.

Before enabling: apply the migration, start the application so queues are declared, review pending
jobs, provide driver shifts/backup coverage, and enable the worker in a controlled environment.
Disabling polling stops new automatic attempts; manual APIs remain usable. The deployment must
run consistent assignment-locking code on all instances before enabling automation.

There is no automatic historical backfill for events already published before this queue existed.
Use reviewed replay of retained booking events or manual dispatch for those trips. Do not delete
job rows to retry them blindly; a completed job may refer to a declined or manually changed trip.

## Files

Paths below are relative to the repository root:

- `core-app/src/main/java/com/mobility/core/dispatch/automatic/`: queue configuration, consumer,
  durable ingestion, worker and validated settings.
- `core-app/src/main/java/com/mobility/core/dispatch/suggest/AutomaticAssignmentService.java`:
  candidate selection and retry/manual outcomes.
- `core-app/src/main/java/com/mobility/core/dispatch/assignment/AssignmentGuard.java`:
  shared commitment checks and deterministic locking.
- `core-app/src/main/java/com/mobility/core/dispatch/assignment/AssignmentService.java`:
  final automatic assignment and guarded manual mutations.
- `core-app/src/main/java/com/mobility/core/shared/DriverScheduleLock.java`: common driver lock.
- `core-app/src/main/java/com/mobility/core/booking/trip/TripService.java`: serialized driver steps.
- `core-app/src/main/java/com/mobility/core/booking/trip/TripsService.java`: system actor attribution.
- `core-app/src/test/java/com/mobility/core/dispatch/AutomaticAssignmentIT.java`: real infrastructure
  and concurrent request tests.
- `core-app/src/test/java/com/mobility/core/dispatch/assignment/AssignmentGuardTest.java`:
  midnight, travel/buffer boundary and unknown-duration checks.
- `core-app/src/test/java/com/mobility/core/dispatch/automatic/RideBookedListenerTest.java`:
  malformed envelope rejection.

## Source and verification

Base revision: `abbed5073504dc1c63fc4be4a0b32341c3a8fe10`, branch
`feature/location-jwt-authentication`. The working tree already contained passenger/location and
booking-mode changes before this increment. This work remains uncommitted.

The previously fetched `origin/main` was `674a6738740bf25bedfef24a868c6757bcd33166`.
Its integration remains unfinished in `/tmp/mobility-auto-assignment`; these changes are tested
against the current feature working tree, not a completed main merge.

Verified locally on 2026-11-11:

- `./mvnw -pl core-app -am verify -Dopenapi.update=true`: **BUILD SUCCESS**;
  13 platform-security tests, 224 core unit tests, 287 integration tests (one skipped), zero failures/errors.
- After the final cleanup and API-description update:
  `./mvnw -pl core-app -am verify -Dopenapi.update=true -Dit.test=AutomaticAssignmentIT,OpenApiSnapshotIT -Dfailsafe.failIfNoSpecifiedTests=false`:
  **BUILD SUCCESS**, including all nine automatic-assignment integration tests and the OpenAPI snapshot test.
- `git diff --check`: passed.
- `scripts/check.sh --no-console --no-backend`: repository structural checks passed.
- `npm run gen:api` and `npm run typecheck` in `web-console`: passed; generated API types match the updated snapshot.
- Full suite log: `/tmp/automatic-assignment-full-tests.log`; final targeted log:
  `/tmp/automatic-assignment-final-tests.log`.

Tests use real PostgreSQL/PostGIS, Redis and RabbitMQ in Testcontainers. Coverage includes
concurrent automatic/manual assignment, primary/backup conflicts, duplicate jobs, real broker delivery,
poison-message dead lettering, retries, horizon deferral, cancellation, airport backup requirements,
driver confirmation through completion, and concurrent trip starts. Unit tests exercise cross-midnight
conflicts, travel/buffer boundaries and unknown destination durations. Location-service/socket tests
were not rerun in this increment; live tracking implementation is unchanged.

No production bookings, commit, push, or deployment are part of this work.
