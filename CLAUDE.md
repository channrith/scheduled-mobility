# CLAUDE.md — Project context for Claude Code

## What we are building
A scheduled & contracted mobility platform for Phnom Penh, Cambodia.
The MVP serves **corporate clients** (NGOs, banks, embassies, BPOs) and **hotels** with
**scheduled rides and airport transfers** (Techo International Airport, ~30 km from the city).
It is NOT a Grab clone. On-demand rides come later.

Core idea: reliability. Every scheduled trip has an assigned driver and (for SLA trips) a backup
driver. A human dispatcher supervises the dispatch engine and handles exceptions.

## Architecture rules (do not violate without asking me)
- **Modular monolith** in Spring Boot. Do NOT create microservices.
  The only separate deployable is `location-service` (WebSocket + driver GPS pings).
- Module boundaries enforced with **Spring Modulith**. Modules talk through public APIs
  or domain events only — never access another module's repositories or tables.
- Modules: `identity`, `driver`, `corporate`, `place`, `pricing`, `booking`, `dispatch`,
  `payment`, `notification`, `safety`, `support`, `audit`.
- One PostgreSQL database, **one schema per module** (e.g. `identity.users`, `booking.trips`).
- Events: **transactional outbox** table → relay → RabbitMQ. No Kafka yet.
- Live driver state and locations live in **Redis** (GEO + hashes), never polled from Postgres.

## Tech stack
- Java 21, Spring Boot 4.x (latest stable), Maven wrapper
- PostgreSQL 16 + PostGIS, Flyway migrations
- Redis, RabbitMQ
- Spring Security with JWT (short access token + rotating refresh token)
- Testcontainers for integration tests, JUnit 5, AssertJ
- Next.js (TypeScript, App Router) for the dispatcher/admin/corporate web console
- Docker Compose for local development
- GitHub Actions for CI

## Domain rules (important)
- **Money**: store as `BIGINT` minor units + `CHAR(3)` currency (USD cents or KHR riel).
  Never use float/double for money. Show USD and KHR equivalents in quotes.
- **Fares are fixed at quote time.** A stored `fare_quote` is immutable and is the final fare.
- **Commission** to drivers is configurable, default 12%, and must never exceed 15%.
- **Promotions are never funded by drivers.** `funded_by` cannot be `DRIVER`.
- **No stored-value wallet.** We do not hold customer funds (licensing reasons).
  Payments: cash, KHQR (via bank gateway, webhook-confirmed), corporate monthly invoice.
- **Payments use a double-entry ledger.** Never update balances directly; post journal
  entries that net to zero.
- **Driver suspension** requires a reason and a notice timestamp (regulatory due process).
- **Timezone**: store `TIMESTAMPTZ` in UTC; display in `Asia/Phnom_Penh` (+07:00).
- **Language**: Khmer (`km`) is the default locale; English (`en`) supported.
  All user-facing strings go through message bundles — no hardcoded text.
- Addresses are often landmark-based: pickups support free-text notes, voice-note keys,
  and a saved `place_id`.

## Coding conventions
- Mutating endpoints accept an `Idempotency-Key` header and are safe to retry.
- Trips and driver status use **explicit state machines** with guarded transitions.
  Every transition writes a `trip_events` row.
- Use **optimistic locking** (`version` column) on trips to prevent double assignment.
- Every staff/admin action writes to `audit.audit_logs`.
- Personal data (ID numbers, bank accounts) encrypted at the application layer.
- API base path: `/api/v1`. Errors use RFC 7807 problem+json.
- Package by feature inside each module, not by layer.
- Write tests with each feature. Integration tests use Testcontainers, not H2.

## Commands
- Start infrastructure: `docker compose up -d`
- Backend tests: `./mvnw verify`
- Run backend: `./mvnw spring-boot:run -pl core-app`
- Web console: `cd web-console && npm run dev`

## How I want you to work
- For each task: **propose a short plan first and wait for my approval** before writing code.
- Work in small steps. After each step, run the tests and tell me the result.
- Suggest a git commit message after each completed step.
- If a requirement is unclear or conflicts with these rules, ask instead of guessing.
- Do not add libraries I haven't listed without explaining why.