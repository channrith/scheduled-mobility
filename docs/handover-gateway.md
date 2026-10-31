# Handover: API gateway, phase 1 (single API host) — and what comes next

Written 2026-10-30, updated 2026-10-31. **Start here** in a new Claude Code session: this is the latest handover.
Status: **gateway phase 1 committed, deployed to staging and verified** (commits `7b4d1d1`, `74aedc3`, `abd4720`).
**Next: gateway phase 2, approved 2026-10-31** (section 10; decisions in section 2). Read this file and `CLAUDE.md`;
earlier context is in `docs/handover-prompt5-booking.md` and the pricing and corporate handovers it links to.

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
| Gateway checks only token validity (2026-10-31) | Missing, expired, forged or wrong-issuer token → 401 at the gateway. **No role rules at the gateway**: they stay in the services' `@PreAuthorize` (one place). Edge role rules only for partner routes in phase 4 |
| The gateway's 401 (2026-10-31) | Same as core-app's: problem+json, code `auth.unauthorized`, `WWW-Authenticate: Bearer`, km/en, so apps and the BFF (refreshes on any 401) see one format |
| Partner credentials (phase 4; changed 2026-10-31) | **API keys** (the user's requirement), header `X-Api-Key`. The gateway exchanges a key through identity for a short-lived JWT (role `PARTNER`, partner id, scopes) and caches it until expiry; services never see API keys. Keys hashed in identity, shown once, revocable from the console, two active per partner for rotation, scopes and a rate limit each, HTTPS only |
| Droplet | Stays 2 GB while in development (4 GB is ~$24/month vs ~$12; settings for it are in `.env.staging.example`) |
| Order | Dispatch was deployed to staging first, then phase 1 |

## 3. Phases

1. **Single API host** (this doc): routing, request IDs, header hygiene, edge errors, deploy wiring. Done.
2. Shared `platform-security` module with JWKS, and the JWT check at the gateway (section 10). In progress.
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

## 8. How the user works (unchanged)

Plan first and wait for approval; small steps with tests after each; suggest commit messages (the user commits,
pushes and deploys); ask before new libraries. The user runs droplet and GitHub commands and pastes the output;
Claude guides and checks it. Don't touch SMS/OTP code or settings. The repo is public: never write secrets or real
staging values into it. Stop local servers started for testing when done (core-app 8080, location-service 8081,
gateway 8090, console 3000), or say which are left running: `scripts/dev.sh up|smoke|down` does the start,
check and stop; `scripts/check.sh` runs the pre-commit checks. Before `git add .`, check `git status` (both workflow
files were once deleted from the working tree by accident).

## 9. Follow-ups

- Gateway phase 2 (section 10), then phases 3 and 4 (section 3).
- Dispatcher console screens (the other candidate for the next prompt).
- A dispatch (Prompt 6) handover doc, if more detail than section 0 is needed.
- Move pings to the API host in the driver app, then drop `LOCATION_HOST`.
- `web-console/.env.example` is ignored by `web-console/.gitignore` (`.env*`); add `!.env.example` if it should
  be tracked.
- Consider 4 GB before load testing; the gateway and location-service each sit at ~75% of 256 MB.

## 10. Gateway phase 2 (approved 2026-10-31; decisions in section 2)

**Duplication today:**

| Piece | core-app | location-service |
|---|---|---|
| Read the public key (PEM text or file) | `identity/token/JwtConfiguration` | `SecurityConfiguration.publicKey()` |
| Token checks: RS256, 30 s clock skew, issuer `mobility-core` | `JwtConfiguration.jwtDecoder` | `SecurityConfiguration.jwtDecoder` |
| `roles` claim → `ROLE_*` authorities | `SecurityConfiguration.jwtAuthenticationConverter` | hand-written copy |
| Request IDs in logs | `shared/web/RequestIdFilter` | `RequestIdFilter` (copy) |

**Step 1, `platform-security` library module + JWKS (no behaviour change on staging):**
- The module: the validators (30 s skew, issuer), roles → authorities, decoders from a **JWKS URL** (preferred) or a
  PEM key (fallback, for tests and until staging switches), servlet and reactive variants, and `RequestIds` (rules
  plus the servlet filter).
- core-app serves its public key(s) at `/.well-known/jwks.json` (permitted without a token; core-app keeps its
  private key and signing in `identity`).
- core-app and location-service use the module; their copies go. location-service accepts `JWT_JWKS_URI` **or** the
  PEM settings (exactly one), so staging keeps working unchanged until step 3.
- Add the module to `.dockerignore` (`scripts/check.sh` checks this). Proof: all existing tests pass unchanged.

**Step 2, JWT check at the gateway** (Spring Security WebFlux, using the module, configured with `JWT_JWKS_URI`):
public without a token: `/api/v1/auth/**`, `GET /api/v1/document-previews/*`, `/actuator/health/**`,
`/actuator/info`, Swagger paths. Everything else needs a valid token, else 401 like core-app's. Valid tokens pass
through unchanged. A bearer token that is present but invalid gets 401 even on a public route (as in core-app).

**Step 3, deploy and docs:** staging Compose: `JWT_JWKS_URI=http://core-app:8080/.well-known/jwks.json` for the
gateway and location-service, `JWT_PUBLIC_KEY` removed from location-service; measure the gateway's memory (raise to
288m if over ~200 MiB); locally the gateway then needs core-app running (README, CLAUDE.md, `scripts/dev.sh`);
update this handover.

**Commits:** `refactor(security): platform-security module (JWKS, token checks, request IDs); core-app serves its
JWKS` · `feat(gateway): reject missing or invalid tokens at the edge; public-route list` · `chore(deploy): JWKS URL
for gateway and location-service; docs`.
