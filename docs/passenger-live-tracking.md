# Passenger live location socket

Implemented 2026-11-01 on backend baseline `51a34caaf93e6aeb4fbefd3c9e20348e7b99f5b1` with uncommitted additions. Not deployed by this task.

## Routes

- Gateway WebSocket: `/api/v1/passenger/trips/{tripId}/location/live`, served by location-service. Use `wss://<gateway-host>` in staging, `ws://localhost:8090` locally. Authenticate with an Authorization Bearer header; no token in query strings.
- Core REST: `GET /api/v1/passenger/trips/{tripId}/tracking-access`. PASSENGER role required. Returns `{tripId, driverId}` and Cache-Control: no-store; returns no GPS.

Only the original booker or rider can access the trip. Another user sees 404; non-passenger roles get 403. Tracking is limited to DRIVER_EN_ROUTE, DRIVER_ARRIVED and IN_PROGRESS with an assigned driver; future/terminal/unassigned trips return 409 `booking.tracking-unavailable`.

The handshake forwards the authenticated JWT to core and verifies trip ownership/assignment before accepting. It binds the session to that assigned driver and the token expiry. The existing receive-only handler sends version 1 `driver.location.snapshot` and `driver.location.updated` messages. Passenger snapshots/events older than 30 seconds or over two seconds in the future are withheld. No passenger GPS, chat or trip mutation messages are accepted.

Before every delivery, the location socket executor rechecks core authorization with the same token and trip. Driver reassignment, cancellation, completion, expired credentials or core failure closes the subscription without sending that location. Access denials fail closed. Token expiration is also enforced by the existing scheduled socket-expiry cleanup. Each delivery authorization performs core reads; the existing bounded delivery executor provides overload protection. This is suitable for the current MVP; do not claim a production capacity benchmark.

Clients must reconnect on foreground/network recovery, refresh access tokens after expiry, discard stale/duplicate/out-of-order events and use REST for authoritative trip state. A policy close may mean access ended, not just token expiry. Passenger iOS stops on repeated policy denial and offers Back/refresh or explicit retry.

## Deployment and tests

Deploy core-app, location-service and api-gateway together. The existing driver routes and payloads are unchanged. No database migration is required. Anonymous staging reads cannot prove this new route is deployed.

```sh
./mvnw -pl core-app,location-service,api-gateway -am \
  -Dtest=PassengerTrackingControllerTest,PassengerLocationHandshakeInterceptorTest,DriverLocationSocketRegistryTest,GatewayRoutingTests \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The targeted checks cover booker/rider access, wrong user/role, active/future/terminal trip states, reassignment rechecks, revoked delivery, and routing through the gateway. They use deterministic synthetic fixtures; no real trip or driver operation is mutated. Real-account end-to-end staging verification remains outstanding.

The iOS workspace contains `docs/mobile-realtime.md`, both app adapters, native MapKit tracking UI, transport recovery tests and a loopback-only socket fixture for simulator QA. No commit, push or deployment was performed.
