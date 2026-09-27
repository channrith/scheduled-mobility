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
The `local` profile adds defaults that match `docker-compose.yml`. For any other environment,
set these variables:

| Variable | Used for |
|----------|----------|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ |
| `OTP_HMAC_SECRET` | Secret for hashing OTP codes (≥ 32 chars) |
| `JWT_PRIVATE_KEY_FILE`, `JWT_PUBLIC_KEY_FILE` | Paths to the RS256 access-token keys, PEM (PKCS#8 / X.509) |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | Alternative: the PEM contents inline. Set either these or the `_FILE` variants. Local profile generates a throwaway pair if neither is set |
| `IDEMPOTENCY_ENCRYPTION_KEY` | Base64 AES-256 key encrypting cached idempotent responses (`openssl rand -base64 32`) |
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

## Authentication

Phone OTP login. There is no SMS gateway yet: the `LoggingSmsSender` stub writes the code to the core-app log.

| Endpoint | |
|---|---|
| `POST /api/v1/auth/otp/request` `{"phone"}` | Sends a 6-digit code (valid 5 min). Accepts `012 345 678`, `+85512345678`, etc. Limits per phone: 1 per 60 s, 5 per hour, 5 wrong attempts per code |
| `POST /api/v1/auth/otp/verify` `{"phone","code"}` | Returns `accessToken` (JWT, 15 min) + `refreshToken` (30 days). Unknown phones are registered as `PASSENGER` |
| `POST /api/v1/auth/refresh` `{"refreshToken"}` | Rotates the refresh token. Replaying an old one revokes the whole session |
| `POST /api/v1/auth/logout` `{"refreshToken"}` | Revokes the session |
| `GET /api/v1/me` | Current user's profile and roles (`Authorization: Bearer …`) |

```bash
curl -X POST localhost:8080/api/v1/auth/otp/request -H 'Content-Type: application/json' -d '{"phone":"012345678"}'
# read the code from the core-app log ("SMS to +855*****678: 123456 ...")
curl -X POST localhost:8080/api/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"phone":"012345678","code":"123456"}'
```

Mutating endpoints accept an optional `Idempotency-Key` header: a retry with the same key and body
replays the original response (`Idempotent-Replayed: true`). Mobile clients should always send one
on `/auth/refresh`, otherwise a retried refresh looks like token theft and ends the session.

Errors are RFC 7807 `application/problem+json` with a machine-readable `code`; `title`/`detail` are
localized from `Accept-Language` (`km` default, `en`).

## Tests

```bash
./mvnw verify
```

- **Unit tests** (`*Tests`, surefire) include `ModularityTests`. It verifies Spring Modulith boundaries
  and writes module diagrams to `core-app/target/spring-modulith-docs/`.
- **Integration tests** (`*IT`, failsafe) boot the full app against real PostGIS, Redis and RabbitMQ
  started by Testcontainers. Docker must be running, but `docker compose` does not have to be up.

CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push to `main` and on pull requests.
