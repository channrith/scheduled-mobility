# Handover: API gateway, phase 1 (single API host) — and what comes next

Written 2026-10-30, updated 2026-11-09. **Start here** in a new Claude Code session: this is the latest handover.
Status: gateway phases 1 and 2 done and verified on staging (sections 4 and 10). **Phase 3, rate limits, is
deployed to staging in shadow mode** (section 11; latest commit `7f3f69d`, 2026-11-09): requests over a limit are
only logged ("would refuse"), nothing is refused yet. Read this file and `CLAUDE.md`;
earlier context is in `docs/handover-prompt5-booking.md` and the pricing and corporate handovers it links to.

## Next session: to do

1. **Read the shadow logs** (a few days after 2026-11-09, with real use):
   `ssh root@146.190.4.99 'cd /opt/mobility && docker compose logs api-gateway | grep "would refuse"'`.
   Which policies show up, for whom (request id → core-app log)? Raise a number in
   `api-gateway/src/main/resources/application.yml` if legitimate users hit it. Each limit must cover the busiest
   legitimate case: `auth` 30 at once then 30/min per IP (an office behind one NAT IP at 9:00, ~2 calls per
   sign-in); `pings` 10 at once then 1/s per driver (a driver app that replays a dead zone's pings one by one
   would hit it: it should batch or space them).
   Also `grep -c "Rate limits are not applied"` should stay 0 or near it (Redis unreachable or a >100 ms call).
2. **Switch to enforce** once the logs are clean: add `GATEWAY_RATE_LIMITS_MODE=enforce` to the droplet's `.env`,
   `docker compose up -d api-gateway`; verify 31 quick `POST /api/v1/auth/otp/request` (or `/auth/refresh`) calls
   from one IP → 429 with `Retry-After`, and console sign-in still works. Update this handover.
3. **Waiting for the user's decision: identity OTP hardening** (section 11, "Proposed"). 6-digit codes and a cap on
   failed verifies per phone. Note section 8: SMS/OTP code and settings are off limits without the user's explicit
   go-ahead, so ask first; check the SMS template before changing the code length.
4. Then phase 4 (partners, API keys) when the first partner needs it, or the other follow-ups in section 9.
5. Small, optional: commit this handover update if it isn't yet (`git status`).

---

## 0. Where the project stands

- Done and on staging: Prompts 0 (skeleton), 1 (identity), 2 (driver), 2.5 (admin console), 3 (corporate +
  place), 4 (pricing, OSRM), 5 (booking + outbox), 6 (dispatch + location pings), gateway phase 1.
- Migrations V1–V14 (Prompt 6 added `V13__booking_duration.sql`, `V14__dispatch.sql`).
- Tests at `abd4720`: core-app 204 unit + 258 integration, location-service 4 + 3, api-gateway 13; console lint,
  typecheck and build clean; OpenAPI snapshot and `web-console/src/lib/api/schema.ts` in sync.
- **Prompt 6 (dispatch) has no handover doc of its own.** In short: `core-app/.../dispatch/` (shift blocks,
  primary + backup assignment with reason codes and confirmation deadlines, greedy suggestions, risk tiers
  GREEN/AMBER/RED from slack and driver pings, exception queue, all under `/api/v1/dispatch` and
  `/api/v1/driver/shifts|trips/{id}/confirm|decline`); booking exposes `booking.Trips` to it; location-service
  takes `POST /api/v1/locations` (DRIVER) into Redis (`driver:location:<userId>`, GEO `driver:positions`).
  API-only, **no dispatcher console screens yet**. On staging every assigned trip within 90 minutes shows
  RED / NO_PING because no driver app sends pings yet: expected.
- Staging: one 2 GB droplet (see `docs/staging.md`). Deploys run from GitHub Actions after CI is green.
- Known local quirk: Maven integration tests need Docker (Testcontainers).

## 1. Why a gateway

The user wants one host for all mobile apps, authentication policy in one place instead of in every service,
and later partner access (hotel PMS, corporate HR systems) with per-client rate limits. Caddy already did TLS
and plain routing; it could not do partner credentials or per-client limits.

## 2. Decisions (approved; don't reopen)

| Decision | Choice |
|---|---|
| Gateway | Spring Cloud Gateway, reactive (WebFlux) flavour: WebSocket proxying and the Redis rate limiter live there |
| Architecture rule | `api-gateway` is the second allowed deployable besides location-service (CLAUDE.md); no business logic, no database |
| Spring Cloud | 2025.1.4 (Oakwood, Gateway 5.0.4): supports Boot 4.1 from 2025.1.2. 2026.0 (Paddington) was only RC on Boot 4.2 |
| BOM scope | Imported in `api-gateway/pom.xml` only, so core-app and location-service keep Boot's dependency versions |
| Where credentials are checked (2026-10-31) | **The gateway** is the single place that checks external credentials (user JWT, partner API key). Not core-app: it is just one backend behind the gateway |
| How services learn who calls: **variant B** (2026-10-31) | The gateway forwards a **signed** user context (a JWT). Services only verify its signature through the shared `platform-security` module (a dependency plus a JWKS URL); they implement no validation of their own. Rejected **variant A** (plain trusted `X-User-Id`/`X-Roles` headers): a request that skips the gateway could claim to be anyone; acceptable only once the `loc.` bypass host is gone, no service port is reachable except from the gateway, and every internal caller uses the gateway |
| Public keys: JWKS (2026-10-31) | core-app serves `/.well-known/jwks.json` (internal network only, not routed by the gateway); services and the gateway are configured with `JWT_JWKS_URI`, none stores a key. Rotation = core-app publishes old and new key (tokens carry `kid`), no restarts. Only core-app holds the private key and signs tokens |
| Automatic decoder setup (2026-10-31) | platform-security builds the decoder from `mobility.jwt.*` (`jwks-uri`, `public-key`, `public-key-file`, `issuer`) in servlet and reactive services; a service writes only its route rules. A service with its own decoder (core-app) keeps it. Environment variable names unchanged (`JWT_JWKS_URI`, `JWT_PUBLIC_KEY`, `JWT_PUBLIC_KEY_FILE`) |
| Gateway checks only token validity (2026-10-31) | Missing, expired, forged or wrong-issuer token → 401 at the gateway. **No role rules at the gateway**: they stay in the services' `@PreAuthorize` (one place). Edge role rules only for partner routes in phase 4 |
| The gateway's 401 (2026-10-31) | Same as core-app's: problem+json, code `auth.unauthorized`, `WWW-Authenticate: Bearer`, km/en, so apps and the BFF (refreshes on any 401) see one format |
| Partner credentials (phase 4; changed 2026-10-31) | **API keys** (the user's requirement), header `X-Api-Key`. The gateway exchanges a key through identity for a short-lived JWT (role `PARTNER`, partner id, scopes) and caches it until expiry; services never see API keys. Keys hashed in identity, shown once, revocable from the console, two active per partner for rotation, scopes and a rate limit each, HTTPS only |
| Droplet | Stays 2 GB while in development (4 GB is ~$24/month vs ~$12; settings for it are in `.env.staging.example`) |
| Order | Dispatch was deployed to staging first, then phase 1 |

## 3. Phases

1. **Single API host** (this doc): routing, request IDs, header hygiene, edge errors, deploy wiring. Done.
2. Shared `platform-security` module with JWKS, and the JWT check at the gateway (section 10). Done.
3. Rate limits: Redis token buckets per route (auth by IP, quotes by user, partners by client plan, pings by
   driver); 429 problem+json with `Retry-After`. The OTP limits in identity stay (business rules).
4. Partners when the first one needs it: API keys in `identity.api_keys` (hashed, scopes, plan), an internal
   exchange endpoint in identity (key → short-lived `PARTNER` JWT) used by the gateway, admin endpoints + audit,
   console screens, `/partner/v1/**`.

## 4. What was built (`api-gateway/`, package `com.mobility.gateway`)

**Routes** (`application.yml`; backends from `CORE_APP_URL`, `LOCATION_SERVICE_URL`; port `SERVER_PORT`, default 8090):

| Path | Backend |
|---|---|
| `/api/v1/locations`, `/api/v1/locations/**`, `/ws/**` (HTTP and WebSocket) | location-service |
| `/api/v1/**` | core-app |
| `/v3/api-docs/**`, `/swagger-ui/**` | core-app (404 there unless `API_DOCS_ENABLED=true`) |
| `/actuator/health`, `/actuator/info` | the gateway's own; backends' actuators are never routed |
| anything else | 404 problem+json |

**Edge filters** (`edge/`, WebFlux `WebFilter`s, so they also run when no route matches):
- `RequestIdFilter`: keeps a client's `X-Request-Id` if it matches `[A-Za-z0-9._-]{8,100}`, else a new UUID; sends
  it to the backend and back to the client; one access line per request (ECS fields `request.id`,
  `http.method`, `url.path`, `http.status`, `duration.ms`). Successful `/actuator/health*` calls are not logged
  (Docker checks every 15 s).
- `InternalHeadersFilter`: removes `X-User-Id`, `X-User-Roles`, `X-Roles`, `X-Client-Id`, `X-Partner-Id` and any
  `X-Internal-*` from incoming requests (`gateway.edge.*` in `EdgeProperties`). Phase 2 sets such headers itself.
- `RequestSizeFilter`: 413 above `gateway.edge.max-request-size` (24MB) by Content-Length.

**Edge errors** (`error/EdgeErrorHandler`, a `WebExceptionHandler` at order -2): problem+json with `code`
(`gateway.not-found`, `gateway.method-not-allowed`, `gateway.too-large`, `gateway.upstream-unavailable` (503,
connection refused), `gateway.upstream-timeout` (504, over `GATEWAY_RESPONSE_TIMEOUT`, 30s), `gateway.error`),
`requestId` and `instance`; Khmer by default, English for `Accept-Language: en` (`i18n/gateway*.properties`).
Backend errors pass through untouched.

**Forwarded headers:** Caddy sets `X-Forwarded-For/-Proto/-Host` (ignoring clients' values). The gateway passes
them through **untouched** for callers matching `trusted-proxies` (private ranges and loopback) and removes them
for anyone else; all its own `x-forwarded.*-enabled` are false. Reason: Spring Cloud Gateway's append mode keeps
only `X-Forwarded-For` entries that match `trusted-proxies` (so the client's public IP would vanish) and would turn
`https` into `https,http`. Backends (`forward-headers-strategy: native`) trust private addresses as proxies and
still see the client IP and https exactly as before.

**Backends:** core-app (`shared/web/RequestIdFilter`) and location-service (`RequestIdFilter`) put the incoming
`X-Request-Id` into MDC as `request.id` (new UUID if missing or odd), first in the filter chain. They do not set it
on responses (the gateway does).

**Local macOS:** profiles `macos-arm64` / `macos-x64` in `api-gateway/pom.xml` add
`netty-resolver-dns-native-macos` at runtime (else Netty logs an ERROR on the first DNS lookup). Never active on
Linux, so never in the image.

## 5. Deployment (staging)

- `deploy/staging/compose.yml`: service `api-gateway` (`API_GATEWAY_MEM_LIMIT` 256m, `API_GATEWAY_JAVA_OPTS`
  `MaxRAMPercentage=50`, health on `:8090/actuator/health/readiness`); Caddy and web-console wait for it.
- `Caddyfile`: `API_HOST` → `api-gateway:8090`. `LOCATION_HOST` still → location-service directly, until the apps
  send pings to the API host; then remove that block and `LOCATION_HOST`.
- web-console BFF: `CORE_API_URL=http://api-gateway:8090`; forwards `x-request-id` to the browser.
- CI builds the `api-gateway` image (`ci.yml` matrix); the deploy workflow checks it exists; `deploy.sh` waits
  for it. `.dockerignore` is an allowlist: a new module must be added there or the image build fails.
- Verified on staging 2026-10-30: all healthy; `/api/v1/me` → 401 from core-app with `x-request-id`;
  `/nothing-here` → `gateway.not-found` in Khmer; console sign-in, pages, a document upload and the map work;
  gateway ~191 MiB of 256 MiB, all containers ~1.1 GB.
- Locally the console still calls core-app directly by default (`CORE_API_URL=http://localhost:8080`); set
  `http://localhost:8090` with the gateway running to go through it.

## 6. Tests

`api-gateway`: 13 tests against in-process Reactor Netty stub backends (`StubBackend`; no mock library):
routing per path, API docs, request ID kept/replaced, internal headers removed, forwarded headers passed through,
localized 404 for unknown paths and backend actuators, own health (and not logged), 504 on a slow backend, 413,
WebSocket proxying, 503 with the backend down (`BackendDownTests`), staging profile. core-app and
location-service: `RequestIdFilterTest` each.

## 7. Pitfalls hit (avoid repeating)

- Root-POM import of the Spring Cloud BOM would change versions in every module: keep it in the gateway's POM.
- `.dockerignore` allowlist: forgetting the new module broke the image build ("Child module ... does not exist").
- Without `trusted-proxies`, `RemoveXForwardedHeadersFilter` strips Caddy's headers; with append, client IPs are
  filtered out (see section 4).
- The access line is written in `doFinally`, after the response: log assertions must wait (Awaitility).
- A flaky core-app test surfaced in CI meanwhile (random test zones overlapping); fixed with `PricingApi.nextSpot()`.
- On a Mac, Netty's DNS ERROR is harmless but alarming; solved by the macOS profiles.
- `scripts/dev.sh` started the gateway without `.env`: no Redis password, so local rate limits silently failed open
  (fixed in `46f36d3`). Fail-open hides a broken limiter: check for "Rate limits are not applied" in the log.
- Lettuce's first connect is synchronous on the subscribing thread: a `block(timeout)` or a Reactor `.timeout()`
  can't cut it short. Never block startup on Redis; subscribe Redis calls off the event loop (`3db6ab3` → `7f3f69d`).
- Health-check `start_period` must outlast a CPU-starved start (1 vCPU, every JVM at once): an "unhealthy" service
  makes `docker compose up` skip everything that `depends_on` it being healthy, and the deploy rolls back.
  Rehearse deploy-setup changes with every app capped (e.g. `cpus: 0.35` in the override), not just plain.

## 8. How the user works (unchanged)

Plan first and wait for approval; small steps with tests after each; suggest commit messages (the user commits,
pushes and deploys); ask before new libraries. The user runs droplet and GitHub commands and pastes the output;
Claude guides and checks it. Don't touch SMS/OTP code or settings. The repo is public: never write secrets or real
staging values into it. Stop local servers started for testing when done (core-app 8080, location-service 8081,
gateway 8090, console 3000), or say which are left running: `scripts/dev.sh up|smoke|down` does the start,
check and stop; `scripts/check.sh` runs the pre-commit checks. Before `git add .`, check `git status` (both workflow
files were once deleted from the working tree by accident).

## 9. Follow-ups

- Gateway phase 3: shadow → enforce (see "Next session"); phase 4 (partners) when needed (section 3).
- Dispatcher console screens (the other candidate for the next prompt).
- A dispatch (Prompt 6) handover doc, if more detail than section 0 is needed.
- Move pings to the API host in the driver app, then drop `LOCATION_HOST`.
- `web-console/.env.example` is ignored by `web-console/.gitignore` (`.env*`); add `!.env.example` if it should
  be tracked.
- Consider 4 GB before load testing; the gateway sits at ~73% of 288 MB (231 MiB peak in a burst), location-service
  ~75% of 256 MB.

## 10. Gateway phase 2 (approved 2026-10-31; decisions in section 2)

**Duplication today:**

| Piece | core-app | location-service |
|---|---|---|
| Read the public key (PEM text or file) | `identity/token/JwtConfiguration` | `SecurityConfiguration.publicKey()` |
| Token checks: RS256, 30 s clock skew, issuer `mobility-core` | `JwtConfiguration.jwtDecoder` | `SecurityConfiguration.jwtDecoder` |
| `roles` claim → `ROLE_*` authorities | `SecurityConfiguration.jwtAuthenticationConverter` | hand-written copy |
| Request IDs in logs | `shared/web/RequestIdFilter` | `RequestIdFilter` (copy) |

**Step 1, `platform-security` library module + JWKS (no behaviour change on staging). Done, `ec1b294`:**
- The module: the validators (30 s skew, issuer), roles → authorities, decoders from a **JWKS URL** (preferred) or a
  PEM key (fallback, for tests and until staging switches), servlet and reactive variants, and `RequestIds` (rules
  plus the servlet filter).
- core-app serves its public key(s) at `/.well-known/jwks.json` (permitted without a token; core-app keeps its
  private key and signing in `identity`).
- core-app and location-service use the module; their copies go. location-service accepts `JWT_JWKS_URI` **or** the
  PEM settings (exactly one), so staging keeps working unchanged until step 3.
- Add the module to `.dockerignore` (`scripts/check.sh` checks this). Proof: all existing tests pass unchanged.

**Step 2, JWT check at the gateway. Done, `524248e`, verified on staging 2026-10-31** (`api-gateway/.../auth/SecurityConfiguration`,
`error/EdgeProblems`; platform-security's `MobilityJwtAutoConfiguration`; location-service on `mobility.jwt.*`; the
staging gateway gets `JWT_JWKS_URI=http://core-app:8080/.well-known/jwks.json` in this step so `main` stays deployable;
`scripts/dev.sh smoke` checks the token at the gateway and logs in when `OTP_FIXED_CODE` is set). The plan:
(Spring Security WebFlux, using the module, configured with `JWT_JWKS_URI`):
public without a token: `/api/v1/auth/**`, `GET /api/v1/document-previews/*`, `/actuator/health/**`,
`/actuator/info`, Swagger paths. Everything else needs a valid token, else 401 like core-app's. Valid tokens pass
through unchanged. A bearer token that is present but invalid gets 401 even on a public route (as in core-app).

**Step 3, deploy and docs. Done, `5c4c791`, verified on staging 2026-11-01** (a passenger's ping got location-service's
own 403 through the gateway and through the direct `loc.` host, so it verifies tokens with core-app's JWKS): location-service defaults to core-app's JWKS
(`JWT_JWKS_URI`, default `http://localhost:8080/.well-known/jwks.json`) and no longer reads the PEM variables, so
exporting `.env` cannot conflict; JWT key files are optional locally (core-app's throwaway key works for everyone);
`scripts/dev.sh` no longer requires them. Verified locally: a token signed by a throwaway key passes the gateway and
location-service, and after core-app restarts with a new key both accept new tokens and reject old ones **without a
restart** (rotation). Gateway memory on staging with Spring Security: 200.6 of 256 MiB, limit unchanged. The plan was:
staging Compose: `JWT_JWKS_URI` for location-service (the gateway has it from step 2), `JWT_PUBLIC_KEY` removed from
location-service; measure the gateway's memory (raise to
288m if over ~200 MiB); locally the gateway then needs core-app running (README, CLAUDE.md, `scripts/dev.sh`);
update this handover.

**Commits:** `refactor(security): platform-security module (JWKS, token checks, request IDs); core-app serves its
JWKS` · `feat(gateway): reject missing or invalid tokens at the edge; public-route list` · `chore(deploy): JWKS URL
for gateway and location-service; docs`.

## 11. Gateway phase 3: rate limits (approved 2026-11-08; deployed in shadow mode 2026-11-09)

**Status history (moved from the top, 2026-11-09):** **Phase 3, rate limits** (section 11): approved; step 1 (gateway limiter) committed `f97ff53` and verified locally by hand; step 2 (BFF forwards the client IP) committed `742edbc`; step 1b (shadow mode) and step 3 (staging Compose, smoke, docs) committed `46f36d3` and **deployed to staging 2026-11-09 in shadow mode** (gateway 211 of 288 MiB; 35 quick calls all passed, "would refuse" logged). Finding: the first Redis use after a gateway start failed to connect once (06:00:51, 6 min after start, first request; Lettuce connects lazily, 500 ms connect timeout on a cold JVM), so the first requests after a restart weren't counted. First fix `3db6ab3` (blocking warm-up on `ApplicationReadyEvent`) **failed its staging deploy and was rolled back to `46f36d3`**: the gateway never turned healthy, so compose never started the console. Cause, reproduced in a rehearsal with every app at 0.35 CPU: the gateway's health check gave a slow start only 30 s + 5 x 15 s; a slower start is marked unhealthy and compose skips everything that waits for it. The blocking warm-up (Lettuce's first connect is synchronous, so its 3 s limit couldn't cut it short) made the start slower still. Second fix, `7f3f69d`, **deployed to staging 2026-11-09 and verified** (all services healthy, "Redis connected for rate limits" logged once): `RedisWarmUp` runs in the background (boundedElastic, retries about 1 min, logs "Redis connected for rate limits"), never blocking startup or readiness; `TokenBuckets.take` subscribes off the event loop so a reconnect can't block Netty and the 100 ms limit holds; Redis `connect-timeout` 500 ms → 2 s; staging `start_period` core-app 90 → 240 s, location-service 60 → 180 s, api-gateway 30 → 180 s. Tests `RedisWarmUpTest` (a 5 s synchronous connect doesn't block startup), `RedisWarmUpIT`. Rehearsed under the CPU limit: healthy in 4 m 37 s of the 5 min budget (core-app 224 s there, 151 s on staging), console up, "would refuse" logged. Not checked: whether `3db6ab3` alone passes with the longer start periods. Next: read the shadow logs over a few days, then `enforce`. Per-phone gateway limit dropped 2026-11-09; identity OTP hardening proposed, not approved. Don't deploy anything after `742edbc` without the step 3 Compose change: staging's gateway would have no `REDIS_HOST`.


**Findings that shape it:**
- The console's BFF calls the gateway without `X-Forwarded-For` (`web-console/src/app/api/core/[...path]/route.ts`,
  `api/auth/*`, `lib/server/session.ts`), so today every console user looks like the web-console container's IP. Any
  per-IP limit would put all console sign-ins in one bucket. Caddy already sets the header for the browser→console hop.
- The gateway has no Redis connection yet; staging Redis is `noeviction`, 64 MB, password-protected.
- Spring Cloud Gateway's built-in `RequestRateLimiter` answers 429 with an empty body and no `Retry-After`, keys by
  route only, and its integer per-second rates can't say "10 per minute" without tricks.
- `LOCATION_HOST` (`loc.`) still reaches location-service directly, so ping limits apply only through the API host
  until that block is removed.

**Approach:** one `RateLimitFilter` (WebFlux `WebFilter`, after Spring Security so it can see the token's `sub`)
picks the first matching policy from `gateway.rate-limits.*` in `application.yml`, builds the key, and runs a small
token-bucket Lua script in Redis (one atomic call per request; keys `rl:<policy>:<key>` with a TTL). When a request
is over the limit: 429 problem+json `gateway.rate-limited` (km/en) with `Retry-After` (whole seconds until the next
token), written by `EdgeProblems`. Allowed requests pass through unchanged. **Fails open:** if Redis is down or slow
(over 100 ms) the request goes through with a WARN log. Redis is left out of readiness, so a Redis outage can't take
the API down.

**Keys:** *user* = the JWT `sub` (only valid tokens get this far). *IP* = the first `X-Forwarded-For` entry when the
caller is a trusted proxy (same `trusted-proxies` pattern), else the remote address.

**Starting policies** (all configurable; first match wins):

| Policy | Matches | Key | Limit (burst / refill) |
|---|---|---|---|
| auth | `/api/v1/auth/**` | IP | 30 burst, 30/min (offices share one NAT IP; identity's per-phone OTP limits stay) |
| quotes | `POST /api/v1/quotes` | user | 20 burst, 30/min (each quote calls OSRM) |
| pings | `POST /api/v1/locations` | user | 10 burst, 1/s (app sends every ~4 s) |
| ws-connect | `/ws/**` | user | 10 burst, 10/min (connection attempts) |
| public | other routes reachable without a token (document previews, API docs) | IP | 60 burst, 60/min |
| default | any other `/api/**` | user | 60 burst, 10/s |
| partners | phase 4 (`/partner/v1/**`, by client plan) | client | not now |

Gateway health and unrouted 404s aren't limited.

**Steps:**
1. *Gateway limiter.* `spring-boot-starter-data-redis-reactive` (new dependency: the reactive Redis client the
   gateway needs; nothing new in the stack), `RateLimitProperties`, the Lua script, `RateLimitFilter`, the
   `gateway.rate-limited` messages plus `error.title.429`. Tests use Testcontainers Redis: per-IP and per-user
   buckets, `Retry-After`, refill, first-match order, IP taken from `X-Forwarded-For` only from trusted proxies,
   fail-open with Redis stopped. Existing tests run with limits off or high.
   **Done, `f97ff53` (2026-11-08); checked by hand locally (burst, Retry-After, per-IP buckets, fail-open):** `api-gateway/.../ratelimit/` (`RateLimitProperties`, `TokenBuckets` +
   `resources/ratelimit/token_bucket.lua`, `ClientIp`, `RateLimitFilter` at order 0, after Security's -100), policies
   in `application.yml` (`GATEWAY_RATE_LIMITS_ENABLED`), Redis `REDIS_HOST/PORT/PASSWORD` with 500 ms connect / 200 ms
   command timeouts, `management.health.redis.enabled: false`. Tests: `RateLimitIT` (Testcontainers Redis, the
   shipped policies), `RateLimitRedisDownTests` (fail open, still healthy), `ClientIpTest`; the older gateway tests
   run with limits off. Bucket key: `rl:<policy>:ip:<addr>` or `rl:<policy>:user:<sub>`.
2. *BFF forwards the client IP.* The BFF passes the incoming `X-Forwarded-For` (set by Caddy) on every call to the
   gateway (proxy, OTP request/verify, refresh, logout); locally it falls back to nothing. Core-app and
   location-service then also see the real client IP from the console.
   **Done, `742edbc` (2026-11-08):** `clientIpHeaders()` in `web-console/src/lib/server/session.ts` passes the
   first `X-Forwarded-For` entry on every BFF call (`api/core/[...path]`, `api/auth/otp/request|verify`,
   `api/auth/logout`, `refreshTokens()`); no header locally means nothing is sent. The console shows the gateway's
   localized `detail`, so no new strings. On staging the console is reachable only through Caddy (no published port),
   so the header can't be spoofed there; locally (`docker compose` publishes :3000) it can, which doesn't matter.
   Console checks need Node >= 20.9 (`nvm use 22`); this Mac's default shell Node is v14.
3. *Deploy and docs.* Staging Compose: `REDIS_HOST`/`REDIS_PASSWORD` for the gateway, `depends_on` Redis; measure
   gateway memory (raise to 288m if over ~220 MiB); `scripts/dev.sh smoke` checks one 429; README, CLAUDE.md, this
   handover. Verify on staging: 31 quick `otp/request` calls from one IP → 429 with `Retry-After`; console sign-in
   still works.
   **Built 2026-11-09, not committed:** gateway gets `REDIS_HOST`, `REDIS_PASSWORD`, `GATEWAY_RATE_LIMITS_MODE`
   (`${...:-shadow}`, so the droplet's `.env` needs no new key) and `depends_on: redis` **started, not healthy** (a
   sick Redis must not keep the API host down); `API_GATEWAY_MEM_LIMIT` default and example 256m → **288m**. The
   droplet's `.env` has no `API_GATEWAY_MEM_LIMIT` (checked 2026-11-09), so the 288m default applies on deploy.
   `scripts/dev.sh` now loads `.env` for the gateway too (before, it had no Redis password, so local limits silently
   failed open), and `smoke` checks one 429 on `/auth/refresh` from a random `X-Forwarded-For` (skipped in shadow).
   README (run command, troubleshooting, settings), CLAUDE.md, `docs/staging.md` (shadow → enforce).
   **Rehearsed** (deploy-rehearsal, real bundle, droplet-like `.env` without the new key): first deploy healthy in
   2.5 min; through Caddy 200 quick calls from one IP all reached core-app, 164 logged "would refuse" (policy,
   request id); Redis stopped → gateway healthy, requests pass with ~100 ms extra (the Redis timeout), WARN logged;
   memory peak 234 MiB of 256 → raised to 288, then 231 MiB under 300 calls; redeploy recreated only the gateway.
   Not rehearsed (deploy.sh unchanged): Caddyfile change, broken image, broken migration, restore.
   **On staging after deploy:** `docker compose logs api-gateway | grep "would refuse"` over real use, console
   sign-in, `docker stats`; then `GATEWAY_RATE_LIMITS_MODE=enforce` and check 31 `otp/request` calls → 429.

**Commits:** `feat(gateway): Redis token-bucket rate limits with 429 problem+json` ·
`fix(console): forward the client IP to the gateway` · `chore(deploy): Redis for the gateway; rate-limit docs`.

**Decided by the user (2026-11-09):** limits stay **fail-open** when Redis is down. **No per-phone limit at the
gateway** (first decided, then dropped the same day): identity already limits OTPs per phone whatever the IP, and
reading bodies plus a copy of `PhoneNumbers` in the gateway isn't worth it. Still open: the numbers.

**Step 1b, shadow mode (approved 2026-11-09; built, not committed):**
- *Shadow mode:* `gateway.rate-limits.mode: enforce|shadow`; shadow logs "would have refused" (policy,
  path, request id) without a 429, so the numbers can be checked against real staging traffic before enforcing.
  Built: `RateLimitProperties.Mode` (`GATEWAY_RATE_LIMITS_MODE`, default `enforce`), log fields `rate_limit.policy`
  and `rate_limit.mode`; buckets are still counted in shadow. Test: `RateLimitShadowIT`.

**Proposed, waiting for approval:**
- *Identity, own small step (not gateway):* the real sign-in gap is guessing codes: 4-digit codes, 5 tries per
  code, 5 codes per hour = 25 guesses per hour per phone (~6% per day of sustained guessing). Fix: 6-digit codes
  (`identity.otp.code-length`, check the SMS template) and optionally a cap on failed verifies per phone across
  codes (e.g. 10 per hour) in `OtpService`, with the existing localized `otp.*` errors.
