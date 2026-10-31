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
  Separate deployables: `location-service` (WebSocket + driver GPS pings) and `api-gateway`.
- **`api-gateway`** (Spring Cloud Gateway, WebFlux) is the single public API host: routing, request IDs
  (`X-Request-Id`, logged as `request.id` by every service), header hygiene and edge errors; later the JWT
  pre-check, rate limits and partner clients. **No business logic and no database in the gateway**; permission
  rules stay in the services (`@PreAuthorize`), which keep checking JWTs themselves.
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
- Spring Cloud Gateway (2025.1 Oakwood; `api-gateway` only, its BOM imported there so other modules keep Boot's versions)
- Testcontainers for integration tests, JUnit 5, AssertJ
- Next.js (TypeScript, App Router) for the dispatcher/admin/corporate web console
- Docker Compose for local development
- GitHub Actions for CI

## Web console (`web-console/`)
- Stack: Next.js 16 (App Router, TS), Tailwind + shadcn/ui (Base UI flavour: `render` prop, not `asChild`),
  TanStack Table v9 + TanStack Query, React Hook Form + Zod, next-intl, lucide-react.
  Read `node_modules/next/dist/docs/` before using Next APIs (`proxy.ts`, async `params`/`cookies()`).
- One route group per area (`(admin)`, later `(dispatch)`, `(corporate)`), sharing the app shell.
- Auth: BFF only. Tokens live in httpOnly cookies; the browser calls `/api/core/...`, never core-app.
  Mutations send an `Idempotency-Key`. No tokens in localStorage.
- API types are generated from `docs/openapi.json` (`npm run gen:api`); don't hand-write API types.
- Role-based UI via `src/lib/auth/roles.ts`, mirroring backend `@PreAuthorize` (the backend enforces).
- i18n: every string in `messages/km.json` (default) and `en.json`; language in a cookie, not the URL.
  Dates in Asia/Phnom_Penh; Khmer dates are formatted by hand (browsers lack Khmer `Intl` data).
- Khmer typography: Kantumruy Pro, line-height ≥ 1.65; no fixed small heights or `overflow-hidden`
  that could clip Khmer marks in buttons, inputs, badges or table cells.
- Native `<select>` for forms/filters (tablet-friendly). Must work on tablets.
- CI: `npm run lint`, `npm run typecheck`, `npm run build`, and API types in sync with `docs/openapi.json`.

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
- Start infrastructure: `docker compose up -d --wait postgres redis rabbitmq` (a plain `up -d` also starts the Dockerised console on :3000)
- Backend tests: `./mvnw verify`
- Run backend: `set -a; source .env; set +a; ./mvnw spring-boot:run -pl core-app -Dspring-boot.run.profiles=local` (port 8080)
- Run location-service: `set -a; source .env; set +a; ./mvnw spring-boot:run -pl location-service` (port 8081)
- Spring Boot does not read `.env`: export it in each terminal. `.env` needs `JWT_PRIVATE_KEY_FILE`/`JWT_PUBLIC_KEY_FILE`
  (shared keys); full local setup in README "Run locally".
- Run gateway: `./mvnw spring-boot:run -pl api-gateway` (port 8090 → core-app :8080, location-service :8081)
- Web console: `cd web-console && npm run dev`

## Continuing in a new session
- Read the latest handover first: `docs/handover-gateway.md` (project status, what was built, the next step and
  any decisions still waiting for me). Older context: `docs/handover-prompt*.md`, `docs/staging.md`.
- Update that handover (or start a new one) when a step is finished or a plan is waiting for my approval.

## How I want you to work
- For each task: **propose a short plan first and wait for my approval** before writing code.
- Work in small steps. After each step, run the tests and tell me the result.
- Suggest a git commit message after each completed step.
- If a requirement is unclear or conflicts with these rules, ask instead of guessing.
- Do not add libraries I haven't listed without explaining why.