# Gateway and location integration

The gateway is Spring Cloud Gateway Server WebFlux from `origin/main` revision
`74aedc3` (gateway introduced in `7b4d1d1`). Both services validate their own JWTs;
the gateway preserves the Authorization header and strips client-supplied internal identity headers.

## Origins and routes

Mobile/Postman: `http://localhost:8090` locally, `https://<API_HOST>` in staging.
Set both Postman `coreBaseUrl` and `locationBaseUrl` to this origin, without `/api/v1`.
There is no path stripping or prefix insertion.

| Gateway path | Upstream | Behavior |
| --- | --- | --- |
| `/api/v1/drivers/me/location` | location-service `:8081` | POST accepted GPS (204); GET latest |
| `/api/v1/drivers/me/location/live` | location-service `:8081` | WebSocket upgrade; DRIVER Bearer header |
| `/api/v1/drivers/me/presence` | location-service `:8081` | GET; reads never renew presence |
| `/api/v1/drivers/me/auth-check` | location-service `:8081` | Local profile diagnostic only |
| `/api/v1/locations` | location-service `:8081` | Existing main compatibility POST (202, `stored`); same validation/save pipeline |
| Other `/api/v1/**`, including `/drivers/me` and availability GET/PUT | core-app `:8080` | Full path, Authorization and Idempotency-Key preserved |
| `/v3/api-docs[/**]`, `/swagger-ui.html`, `/swagger-ui/**` | core-app `:8080` | Subject to backend API docs setting |
| `/actuator/health[/**]` | gateway itself `:8090` | Public gateway health, not aggregated backend health |
| `/actuator/info` | gateway itself | Internal; staging Caddy blocks public access |
| Other actuator/unknown paths | none | 404; backend actuators are not proxied |

Gateway upstreams `CORE_APP_URL` and `LOCATION_SERVICE_URL` are origins without path suffixes.
Locally these default to `http://localhost:8080` and `http://localhost:8081`.
Staging Compose sets `http://core-app:8080` and `http://location-service:8081`.
Location's `CORE_BASE_URL` must point directly to core-app (`http://localhost:8080` locally,
`http://core-app:8080` in staging). Its profile lookup calls `/api/v1/drivers/me` with the original
Bearer token; it must not point at the location service or include `/api/v1`.
`CORE_API_URL` on the console points to the gateway, separately from `CORE_BASE_URL`.
The launcher derives upstream ports from `CORE_SERVER_PORT`/`LOCATION_SERVER_PORT` and starts
the gateway on `GATEWAY_SERVER_PORT` (8090).

Staging Caddy's API host proxies to `api-gateway:8090`; upgrades pass through Caddy and Gateway.
The legacy `LOCATION_HOST` still proxies directly to location-service for existing clients.
Only Caddy exposes public application ports. Caddy replaces untrusted incoming X-Forwarded headers;
Gateway accepts them only from configured loopback/private trusted proxies and does not append another
hop. Backends use Tomcat native forwarding under staging with private proxies trusted. Do not publish
service/gateway ports to the internet or extend this trust boundary to untrusted workloads.
Gateway connects within `GATEWAY_CONNECT_TIMEOUT_MS` (2000) and waits for HTTP responses for
`GATEWAY_RESPONSE_TIMEOUT` (30s). Upgraded sockets use WebSocket forwarding, not the HTTP response
timeout. No short Caddy stream timeout is configured. The location service closes sockets at JWT expiry
(code 1008); reconnect with a fresh token receives the latest snapshot. Tokens never belong in query strings.

## GPS and Redis

Both existing upload paths require accuracy and recordedAt, a valid DRIVER JWT, and an APPROVED
profile from core-app. They share age, accuracy, jump and concurrent-baseline validation. Ignored
old/duplicate measurements do not publish events or renew presence. One Lua operation writes latest
location, TTL, presence, the existing dispatch hash/GEO mirror, and publishes the accepted event.
The mirror remains keyed by **user ID**, while canonical location/presence and socket routing use
**driver ID**. This deployment uses standalone Redis; the dispatch mirror and GEO index span hash
slots and are not compatible with Redis Cluster without a future data-contract change.
GEO-compatible latitude bounds apply (`±85.05112878`).
`LOCATION_EVENTS_CHANNEL` must match the writer and subscriber, and should differ between environments.
`LOCATION_PRESENCE_TIMEOUT` defaults to 60s. Presence is independent of availability. Reads do not
change either TTL. Request/event logging omits credentials and GPS bodies.

## Postman / curl

Obtain a DRIVER access token through the ordinary OTP flow and store it privately in
`DRIVER_ACCESS_TOKEN`. Use an approved fixture for GPS. Import these curl requests into Postman;
set the Authorization tab to Bearer Token. Do not enable verbose curl or share saved tokens.

```bash
export GATEWAY_URL=http://localhost:8090
# Staging: export GATEWAY_URL=https://<API_HOST>
curl "$GATEWAY_URL/actuator/health"
curl -i "$GATEWAY_URL/api/v1/drivers/me/location" -H 'Authorization: Bearer invalid'

curl -i -X POST "$GATEWAY_URL/api/v1/drivers/me/location" \
  -H "Authorization: Bearer $DRIVER_ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "{\"latitude\":11.5564,\"longitude\":104.9282,\"accuracyMeters\":10,\"recordedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"

curl "$GATEWAY_URL/api/v1/drivers/me/location" -H "Authorization: Bearer $DRIVER_ACCESS_TOKEN"
curl "$GATEWAY_URL/api/v1/drivers/me/presence" -H "Authorization: Bearer $DRIVER_ACCESS_TOKEN"
curl "$GATEWAY_URL/api/v1/drivers/me/availability" -H "Authorization: Bearer $DRIVER_ACCESS_TOKEN"

# Substitute expectedVersion from the preceding availability GET.
curl -X PUT "$GATEWAY_URL/api/v1/drivers/me/availability" \
  -H "Authorization: Bearer $DRIVER_ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" --data '{"status":"UNAVAILABLE","expectedVersion":0}'
```

Postman WebSocket URL: `ws://localhost:8090/api/v1/drivers/me/location/live`
(staging: `wss://<API_HOST>/api/v1/drivers/me/location/live`). Add
`Authorization: Bearer <driver access token>` as a handshake header. Connect before submitting a new
GPS timestamp to receive `driver.location.updated`; reconnect to receive `driver.location.snapshot`.
Reads, ignored GPS and arbitrary socket messages do not generate accepted GPS events.

## Reproducible local checks

```bash
./mvnw verify
cd web-console && npm ci && npm run gen:api && npm run typecheck
```

The full local runtime check uses the synthetic approved fixture, the real three applications,
and Compose PostGIS/Redis/RabbitMQ. Start core-app with `LOCAL_APPROVED_DRIVER_SEED_ENABLED=true`
and local-only `OTP_FIXED_CODE=1234`, location-service with `LOCATION_PRESENCE_TIMEOUT=5s`, and
the gateway. Export `.env` before starting applications; use shared local JWT key files.
The smoke test requests an OTP, verifies the fixture, tests HTTP and WebSocket behavior, then logs
out the refresh session. It prints only check names, never tokens or GPS responses:

```bash
SMOKE_OTP_CODE=1234 SMOKE_JWT_PRIVATE_KEY_FILE="$PWD/jwt-private.pem" java scripts/GatewaySmoke.java
```

The short-lived token used to test socket expiry is signed with the local development key.
Run this fixture check only against the local deployment. Restore the normal presence timeout on
restart. Staging Caddy/TLS behavior requires a separate deployed check; local gateway success does
not establish staging runtime success.

## Verification record (local, 2026-10-31 Asia/Phnom_Penh)

Source inspection: confirmed main contains the existing WebFlux gateway; reconciled JWT/security,
canonical and compatibility GPS handlers, driver directory, Redis writes/event subscription,
snapshots/expiry, trusted-proxy forwarding and service origins. All merge conflicts are resolved.
No merge commit was created.

Successful checks: core-app verify (208 unit + 260 integration), location-service verify
(25 unit + 12 integration), gateway verify (14 tests): **519 tests, zero failures/errors/skips**.
Generated OpenAPI snapshot and TypeScript types; `npm run typecheck` passed. Local/staging Compose
and Caddy 2.10.2 configuration validation passed; launcher shell syntax passed. Existing whitespace
warnings in imported map files and the pre-existing location response file were left unchanged.

Successful real local runtime check through :8090, repeated against the packaged location-service:
public gateway health, hidden actuator env, invalid JWT rejection at core/location, ordinary OTP login,
approved profile lookup, accepted GPS upload, latest GET, presence refresh/expiry under repeated reads,
compatibility duplicate rejection, live delivery of the real Redis GPS event, reconnect snapshot,
availability GET/PUT, socket close 1008 at token expiry, refresh-session logout. The short presence
TTL was a local test setting. Driver-specific delivery and pre-write dispatch-key failure behavior
also have automated regression tests.

Remaining limits: no staging deployment or live Caddy/TLS verification was performed. Audience,
required-exp hardening and JWKS rotation are reviewed in `jwt-architecture-review.md` and remain
proposed changes. Redis must remain standalone for the atomic dispatch mirror. No commit, push or
deployment was performed; pre-existing working-tree changes are preserved with the pre-merge stash
retained as an additional backup.
