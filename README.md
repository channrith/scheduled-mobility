# Mobility

Scheduled & contracted mobility platform for Phnom Penh. It serves corporate clients and hotels
with scheduled rides and airport transfers. See [CLAUDE.md](CLAUDE.md) for architecture and domain rules.

## Repository layout

| Path                | What it is                                                                        |
|---------------------|-----------------------------------------------------------------------------------|
| `core-app/`         | Spring Boot modular monolith (Spring Modulith). One package per module under `com.mobility.core`. |
| `location-service/` | Separate deployable for driver GPS pings and WebSocket live location.             |
| `docker-compose.yml`| Local PostgreSQL 16 + PostGIS, Redis, RabbitMQ.                                   |

Modules in `core-app`: `identity`, `driver`, `corporate`, `place`, `pricing`, `booking`,
`dispatch`, `payment`, `notification`, `safety`, `support`, `audit`. Each owns a Postgres
schema of the same name (created by `core-app/src/main/resources/db/migration/V1__init_schemas.sql`).

## Prerequisites

- Java 21
- Docker (Desktop or Engine) with Docker Compose v2

Maven is not required: use the wrapper `./mvnw`.

## Run locally

```bash
# 1. Configure local credentials (optional: the defaults match .env.example)
cp .env.example .env

# 2. Start infrastructure
docker compose up -d

# 3. Run the core app with the local profile (http://localhost:8080)
./mvnw spring-boot:run -pl core-app -Dspring-boot.run.profiles=local

# 4. Run the location service (http://localhost:8081)
./mvnw spring-boot:run -pl location-service
```

Flyway applies migrations automatically on core-app startup.

| URL                                    | What                                   |
|----------------------------------------|----------------------------------------|
| http://localhost:8080/actuator/health  | core-app health (db, redis, rabbit)    |
| http://localhost:8081/actuator/health  | location-service health                |
| http://localhost:15672                 | RabbitMQ management UI (`mobility` / `mobility`) |

Stop infrastructure with `docker compose down`. Add `-v` to also delete the data volumes.

## Configuration

`application.yml` contains no secrets. Every host and credential comes from an environment variable.
The `local` profile adds defaults that match `docker-compose.yml`. The `staging` profile (both apps) trusts
`X-Forwarded-*` headers from a reverse proxy on the private network, logs JSON (ECS) to stdout and turns the
API docs on. For any environment other than `local`, set these variables:

| Variable | Used for |
|----------|----------|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ |
| `OTP_HMAC_SECRET` | Secret for hashing OTP codes (≥ 32 chars) |
| `OTP_FIXED_CODE` | **Test environments only.** Every phone logs in with this code (e.g. `1234`) instead of a random one; rate limits still apply. Anyone who knows a phone number can then log in as that user, so never use it with real personal data. Startup fails if a `prod`/`production` profile is active |
| `BOOTSTRAP_ADMIN_PHONE`, `BOOTSTRAP_ADMIN_NAME` | Granted ADMIN at startup while no ADMIN exists yet (audited, actor = system); ignored afterwards, so it can stay set |
| `JWT_PRIVATE_KEY_FILE`, `JWT_PUBLIC_KEY_FILE` | Paths to the RS256 access-token keys, PEM (PKCS#8 / X.509) |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | Alternative: the PEM contents inline. Set either these or the `_FILE` variants. Local profile generates a throwaway pair if neither is set |
| `IDEMPOTENCY_ENCRYPTION_KEY` | Base64 AES-256 key encrypting cached idempotent responses (`openssl rand -base64 32`) |
| `PII_ENCRYPTION_KEY` | Base64 AES-256 key for personal data: national ID, bank account, document files (`openssl rand -base64 32`) |
| `PII_HASH_SECRET` | HMAC secret (≥ 32 chars) for blind indexes, e.g. duplicate national ID detection |
| `STORAGE_TYPE` | `local` (default) or `s3`. Documents are encrypted by the app before they reach either backend |
| `STORAGE_LOCAL_DIR` | `local`: where uploaded documents are stored (default `./var/storage`, git-ignored) |
| `STORAGE_S3_ENDPOINT`, `STORAGE_S3_REGION`, `STORAGE_S3_BUCKET`, `STORAGE_S3_ACCESS_KEY`, `STORAGE_S3_SECRET_KEY` | `s3`: any S3-compatible store, e.g. endpoint `https://sgp1.digitaloceanspaces.com`, region `sgp1`. Endpoint empty = AWS S3. The bucket must exist and be private |
| `SERVER_PORT` | HTTP port (default 8080 core-app, 8081 location-service) |

Generate JWT keys (`*.pem` is git-ignored):
```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

Spring Boot does not read `.env` itself; export it before running (`spring-boot:run` uses the repo
root as working directory, so relative `_FILE` paths resolve from there):
```bash
set -a; source .env; set +a
./mvnw spring-boot:run -pl core-app -Dspring-boot.run.profiles=local
```

Note: `V1` runs `CREATE EXTENSION postgis / pg_trgm`, which needs a role allowed to create
extensions. On managed Postgres, pre-install the extensions or grant the migration role accordingly.

## Container images

One `Dockerfile` builds either deployable (`MODULE=core-app` or `location-service`). Tests are skipped in the
image build; CI runs `./mvnw verify` first. `.dockerignore` is an allowlist, so `.env`, `*.pem` and `var/`
never enter the build context.

```bash
docker build --build-arg MODULE=core-app -t mobility/core-app .
docker build --build-arg MODULE=location-service -t mobility/location-service .
```

Images run as a non-root user with the heap at 75% of the container memory limit. Configuration is the same
environment variables as above, plus `SPRING_PROFILES_ACTIVE=staging`. Mount JWT keys read-only and point
`JWT_PRIVATE_KEY_FILE` / `JWT_PUBLIC_KEY_FILE` at them.

## Staging deployment

`deploy/staging/` runs everything on one server with Docker Compose: Caddy (HTTPS), both apps,
PostgreSQL + PostGIS, Redis and RabbitMQ. Documents go to a Docker volume (`STORAGE_TYPE=local`) or an
S3-compatible bucket (`s3`); once buckets exist, `backup.sh` uploads a nightly `pg_dump` to a second bucket. `generate-secrets.sh` fills `.env` from `.env.staging.example`. The apps
connect as a non-superuser `mobility_app`; `postgres/init` creates it and the extensions on first start. Setup and operations: [docs/staging.md](docs/staging.md).

## Authentication

Phone OTP login. There is no SMS gateway yet: the `LoggingSmsSender` stub writes the code to the core-app log.

| Endpoint | |
|---|---|
| `POST /api/v1/auth/otp/request` `{"phone"}` | Sends a 4-digit code (valid 5 min). Accepts `012 345 678`, `+85512345678`, etc. Limits per phone: 1 per 60 s, 5 per hour, 5 wrong attempts per code |
| `POST /api/v1/auth/otp/verify` `{"phone","code"}` | Returns `accessToken` (JWT, 15 min) + `refreshToken` (30 days). Unknown phones are registered as `PASSENGER` |
| `POST /api/v1/auth/refresh` `{"refreshToken"}` | Rotates the refresh token. Replaying an old one revokes the whole session |
| `POST /api/v1/auth/logout` `{"refreshToken"}` | Revokes the session |
| `GET /api/v1/me` | Current user's profile and roles (`Authorization: Bearer …`) |

```bash
curl -X POST localhost:8080/api/v1/auth/otp/request -H 'Content-Type: application/json' -d '{"phone":"012345678"}'
# read the code from the core-app log ("SMS to +855*****678: 4821 ...")
curl -X POST localhost:8080/api/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"phone":"012345678","code":"4821"}'
```

Mutating endpoints accept an optional `Idempotency-Key` header: a retry with the same key and body
replays the original response (`Idempotent-Replayed: true`). Mobile clients should always send one
on `/auth/refresh`, otherwise a retried refresh looks like token theft and ends the session.

Errors are RFC 7807 `application/problem+json` with a machine-readable `code`; `title`/`detail` are
localized from `Accept-Language` (`km` default, `en`).

## Drivers

Staff register drivers; the driver then logs in with phone OTP (the `DRIVER` role is granted on registration).

```
PENDING ──submit──► DOCS_SUBMITTED ──training──► TRAINING ──approve──► APPROVED ⇄ SUSPENDED
   └──────────────────────┴───────────── reject ──────┴──► REJECTED (final)
```

| Transition | Who | Guard |
|---|---|---|
| submit (`POST /drivers/me/submit`) | driver | NATIONAL_ID, DRIVING_LICENSE, PROFILE_PHOTO uploaded |
| training | ADMIN | those documents approved and not expired |
| approve | ADMIN | + assigned vehicle with approved VEHICLE_REGISTRATION and VEHICLE_INSURANCE |
| reject | ADMIN | reason (≥ 10 chars) |
| suspend | ADMIN, SAFETY_OFFICER | APPROVED only; reason (≥ 10 chars); `noticeAt` = when the driver was notified (default now, not in the future) |
| reinstate | ADMIN | reason; still meets every approval requirement |

Every transition writes `driver.driver_status_history`; staff actions write `audit.audit_logs`.

| Endpoint | Roles |
|---|---|
| `POST /api/v1/admin/drivers` (phone, fullName, nationalId?, bank*?) | ADMIN |
| `GET /api/v1/admin/drivers?status=&page=&size=`, `GET …/{id}` | ADMIN, DISPATCHER, SUPPORT, SAFETY_OFFICER |
| `POST …/{id}/training` · `/approve` · `/reject` · `/reinstate` | ADMIN |
| `POST …/{id}/suspend` `{reason, noticeAt?}` | ADMIN, SAFETY_OFFICER |
| `POST/DELETE …/{id}/vehicle-assignment` `{vehicleId}` | ADMIN |
| `POST …/{id}/documents/{docId}/approve` · `/reject` | ADMIN |
| `GET …/{id}/documents/{docId}/content` (audited) | ADMIN, SAFETY_OFFICER |
| `POST /api/v1/admin/vehicles`, `GET …/{id}` | ADMIN (create), staff (view) |
| `GET /api/v1/drivers/me`, `GET/POST /api/v1/drivers/me/documents` (multipart), `POST …/submit` | DRIVER |

Documents: JPEG, PNG or PDF (checked by content, not by the declared type), max 10 MB, encrypted at rest.
National ID and bank account numbers are encrypted in the database and always shown masked (`•••••5678`).
A daily job (06:00 Phnom Penh, `driver.documents.expiry-check-cron`) flags approved documents expiring within
30 days and publishes `DriverDocumentExpiringSoon` (the notification module will act on it).

To try it locally you need an ADMIN: start the app once with `BOOTSTRAP_ADMIN_PHONE=012000001`, then log in
with that phone.

## API documentation

The OpenAPI 3 spec is generated from the controllers (springdoc), so it always matches the code.

| URL (local profile) | What |
|---|---|
| http://localhost:8080/swagger-ui.html | Interactive docs. Click **Authorize** and paste an access token (without `Bearer`) |
| http://localhost:8080/v3/api-docs/all | Full spec (JSON); also `/auth`, `/driver-app`, `/admin` groups |

Every operation documents its required roles (`x-required-roles`, from `@PreAuthorize`), bearer
security, `Accept-Language`, `Idempotency-Key` (mutating endpoints) and its problem+json errors.
Endpoint-specific errors are declared with `@ProblemResponse` on the controller method.

The docs are **off outside the `local` profile** (a public spec maps out the admin API); set
`API_DOCS_ENABLED=true` to turn them on in a non-production environment.

`docs/openapi.json` is a committed snapshot of the spec. `OpenApiSnapshotIT` fails the build when the
API changes and the snapshot was not updated, so API changes show up in code review. After an
intended change:
```bash
./mvnw verify -Dopenapi.update=true   # then review and commit docs/openapi.json
```
The web console can generate TypeScript types from it, e.g. `npx openapi-typescript ../docs/openapi.json -o src/api/schema.ts`.

## Tests

```bash
./mvnw verify
```

- **Unit tests** (`*Tests`, surefire) include `ModularityTests`. It verifies Spring Modulith boundaries
  and writes module diagrams to `core-app/target/spring-modulith-docs/`.
- **Integration tests** (`*IT`, failsafe) boot the full app against real PostGIS, Redis and RabbitMQ
  started by Testcontainers. Docker must be running, but `docker compose` does not have to be up.

CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push to `main` and on pull requests.
After `verify` passes on `main`, CI also pushes both images to GHCR
(`ghcr.io/channrith/scheduled-mobility/{core-app,location-service}`), tagged with the full commit SHA and `main`.
Deploy to staging with **Actions → Deploy staging → Run workflow** (`.github/workflows/deploy-staging.yml`):
it syncs `deploy/staging/` to the server and runs `deploy.sh <sha>`, which rolls back to the previous tag if the
apps do not become healthy. It needs the `staging` environment secrets `STAGING_HOST`, `STAGING_SSH_USER`,
`STAGING_SSH_KEY` and `STAGING_SSH_KNOWN_HOSTS` (see `docs/staging.md`).
