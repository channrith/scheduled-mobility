# Handover: API gateway, phase 1 (single API host)

Written 2026-10-30. For a new Claude Code session that needs the gateway's context without the earlier
conversation. Status: **phase 1 deployed to staging and verified**; this doc ships with its last commit (section 8).
Read this file and `CLAUDE.md`; earlier context is in `docs/handover-prompt5-booking.md` and the pricing and
corporate handovers it links to.

---

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
| Services still check JWTs | Yes, defense in depth: the gateway's check (phase 2) is extra, not a replacement |
| Partner credentials (phase 4) | OAuth2 client credentials → short JWT with role `PARTNER`; static API keys only for partners that cannot do OAuth |
| Droplet | Stays 2 GB while in development (4 GB is ~$24/month vs ~$12; settings for it are in `.env.staging.example`) |
| Order | Dispatch was deployed to staging first, then phase 1 |

## 3. Phases

1. **Single API host** (this doc): routing, request IDs, header hygiene, edge errors, deploy wiring. Done.
2. Shared `platform-security` module (the JWT resource-server setup now copied in core-app and location-service,
   plus the two `RequestIdFilter`s) and the JWT check at the gateway with an explicit public-route list.
3. Rate limits: Redis token buckets per route (auth by IP, quotes by user, partners by client plan, pings by
   driver); 429 problem+json with `Retry-After`. The OTP limits in identity stay (business rules).
4. Partner clients when the first partner needs it: `identity.api_clients` (hashed secrets, scopes, plan),
   `POST /api/v1/auth/token` (client credentials), admin endpoints + audit, console screens, `/partner/v1/**`.

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

## 8. Suggested commit (this doc and the last phase-1 changes)

```
docs: api-gateway in CLAUDE.md, staging doc and handover; quiet health-check logging
```

## 9. Follow-ups

- Phase 2 next (shared `platform-security`, JWT pre-check at the gateway).
- Move pings to the API host in the driver app, then drop `LOCATION_HOST`.
- `web-console/.env.example` is ignored by `web-console/.gitignore` (`.env*`); add `!.env.example` if it should
  be tracked.
- Consider 4 GB before load testing; the gateway and location-service each sit at ~75% of 256 MB.
