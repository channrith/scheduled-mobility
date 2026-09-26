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
| `SERVER_PORT` | HTTP port (default 8080 core-app, 8081 location-service) |

Note: `V1` runs `CREATE EXTENSION postgis / pg_trgm`, which needs a role allowed to create
extensions. On managed Postgres, pre-install the extensions or grant the migration role accordingly.

## Tests

```bash
./mvnw verify
```

- **Unit tests** (`*Tests`, surefire) include `ModularityTests`. It verifies Spring Modulith boundaries
  and writes module diagrams to `core-app/target/spring-modulith-docs/`.
- **Integration tests** (`*IT`, failsafe) boot the full app against real PostGIS, Redis and RabbitMQ
  started by Testcontainers. Docker must be running, but `docker compose` does not have to be up.

CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push to `main` and on pull requests.
