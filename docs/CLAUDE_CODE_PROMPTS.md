# Claude Code prompts — Phnom Penh scheduled mobility MVP

## How to use
1. Create an empty folder for the project and put `CLAUDE.md` in its root.
   Claude Code reads `CLAUDE.md` automatically, so you don't need to repeat those rules.
2. Paste **one prompt at a time**, in order. Let Claude Code plan, approve the plan,
   let it build and test, review the result, commit, then move to the next prompt.
3. Don't paste everything at once. Smaller steps give much better code.
4. If a step goes wrong, say what's wrong and ask it to fix that step before continuing.
5. Between prompts: test the feature yourself end-to-end, run `scripts/check.sh`,
   commit, and make sure Claude Code has written or updated the handover doc
   (`docs/handover-*.md`) and CLAUDE.md with any new conventions.
6. From Prompt 7 onward, every prompt starts by reading CLAUDE.md and the latest
   `docs/handover-*.md`, because the architecture changed after Prompt 6
   (api-gateway, platform-security, JWKS).

## Progress
- [x] Prompt 0 — Project scaffold
- [x] Prompt 1 — Identity and authentication
- [x] Prompt 2 — Drivers, vehicles, documents
- [x] Prompt 2.5 — Admin web console for driver onboarding
- [x] Prompt 2.6 — Update CLAUDE.md with frontend conventions
- [x] Prompt 3 — Corporate accounts and places (+ admin screens)
- [x] Prompt 4 — Pricing and fare quotes (+ admin screens)
- [x] Prompt 5 — Booking and trip lifecycle
- [x] Prompt 6 — Dispatch and at-risk detection
- [x] Since Prompt 6 — api-gateway, platform-security, JWKS, dev/check scripts, handover docs
- [ ] Prompt 7 — Location service (realtime, through the gateway)
- [ ] Prompt 8 — Dispatcher console and corporate portal
- [ ] Prompt 9 — Telegram bot (public webhook route)
- [ ] Prompt 10 — Payments, ledger, payouts, invoices (public webhook route)
- [ ] Prompt 11 — Notifications, safety and audit
- [ ] Prompt 12 — Production readiness
- [ ] Gateway phase 3 — rate limits. **Must be done before any public route
      (tracking link, Telegram or payment webhooks) is exposed in a real environment.**
- [ ] Gateway phase 4 — partner API keys, when the first partner needs it
      (decide whether hotels book through the corporate portal or a partner API)

---

## Prompt 0 — Project scaffold

```
Read CLAUDE.md. Set up the project skeleton:

- Maven multi-module project: `core-app` (the modular monolith) and `location-service`.
- In core-app, create empty Spring Modulith modules for: identity, driver, corporate,
  place, pricing, booking, dispatch, payment, notification, safety, support, audit.
  Add a Spring Modulith test that verifies module boundaries.
- docker-compose.yml with PostgreSQL 16 + PostGIS, Redis, RabbitMQ (with management UI).
- Flyway configured, with a first migration that creates one schema per module
  and enables the postgis and pg_trgm extensions.
- application.yml with local profile; secrets from environment variables.
- A health endpoint and a Testcontainers-based integration test that boots the app.
- GitHub Actions workflow that runs ./mvnw verify.
- README with how to run everything locally.

Plan first and wait for my approval.
```

---

## Prompt 1 — Identity and authentication

```
Implement the identity module:

- Tables: identity.users (phone_e164 unique, telegram_id, full_name, preferred_lang
  default 'km', status), identity.user_roles (user_id, role, scope_id).
- Roles: PASSENGER, DRIVER, CORPORATE_ADMIN, CORPORATE_BOOKER, DISPATCHER,
  SUPPORT, SAFETY_OFFICER, ADMIN.
- Phone OTP login: POST /api/v1/auth/otp/request and /otp/verify.
  Use an SmsSender interface with a logging stub implementation for now.
  OTP stored hashed in Redis with 5-minute expiry and rate limiting per phone.
- JWT access token (15 min) + rotating refresh token (POST /auth/refresh),
  refresh tokens stored hashed so they can be revoked.
- GET /api/v1/me.
- Method-level authorization helpers, including a check that corporate users
  can only access their own corporate_id (scope_id).
- Tests for login, refresh rotation, rate limiting, and role checks.

Plan first.
```

---

## Prompt 2 — Drivers, vehicles, documents

```
Implement the driver module:

- Tables: driver.drivers, driver.vehicles, driver.driver_vehicle_assignments,
  driver.driver_documents (follow the conventions in CLAUDE.md).
- Vehicle classes: MOTO, TUKTUK_REMORK, TUKTUK_RICKSHAW, CAR, SUV, VAN.
- Driver onboarding status state machine:
  PENDING → DOCS_SUBMITTED → TRAINING → APPROVED, plus REJECTED and SUSPENDED.
- Document upload through a StorageService interface (local filesystem implementation
  for dev, S3-compatible later). Admin review: approve/reject with reviewer and time.
- Suspension requires a reason and records suspension_notice_at. Write to the audit log.
- Encrypt national ID number and bank account fields at the application layer.
- A scheduled job that flags documents expiring within 30 days.
- Admin endpoints: list drivers by status, approve, suspend, reinstate.
- Driver endpoint: view own profile and upload documents.
- Tests for the state machine and suspension rules.

Plan first.
```

---

## Prompt 2.5 — Admin web console for driver onboarding

Built early so operations staff can register drivers in person during the pilot.

```
Read CLAUDE.md. Prompts 0, 1 and 2 are done. Before continuing with pricing and booking,
I want the admin web console for driver onboarding, so my operations staff can register
drivers in person.

Part A — backend additions (core-app):

Step 1 — check what already exists before writing anything:
- An ADMIN account already exists. Do NOT create an admin bootstrap or seed.
  Check how the existing admin was created. If any phone number, OTP, token or other
  credential is hardcoded in code, config or Flyway migrations, tell me and propose
  a fix instead of leaving it.
- Review the driver and identity modules from Prompts 1 and 2. List which of the
  endpoints below already exist, which exist but need changes, and which are missing.
  Only add or change what is needed. Do not duplicate existing endpoints.

Step 2 — admin endpoints needed (ADMIN role, all audit-logged):
- Create a driver on their behalf (phone, full name, national ID, license no.,
  home zone, preferred language). If the phone already belongs to a user,
  add the DRIVER role instead of creating a duplicate user.
- Edit driver details.
- Create/edit vehicles and assign a vehicle to a driver (with valid_from/valid_to),
  including the case where the vehicle owner is not the driver (rented tuk-tuks).
- Upload documents on the driver's behalf (photo or PDF, max size limit,
  allowed MIME types checked on the server).
- Short-lived signed URL to preview a document; never expose storage paths.
- List drivers with filters (onboarding status, vehicle class, documents expiring soon)
  and pagination; search by name, phone or plate.
- Onboarding actions: move to TRAINING, approve, reject (reason required),
  suspend (reason required, records notice time), reinstate.

Step 3 — supporting changes:
- Configure CORS for the web console's local origin only.
- In the local profile, the SMS stub should log the OTP so I can log in during development.
- Tests for every new or changed endpoint.

Part B — web-console:

Use this frontend stack (do not substitute without asking):
- Next.js (latest stable, App Router, TypeScript), created with create-next-app
- Tailwind CSS + shadcn/ui components (initialize with the shadcn CLI)
- TanStack Table for data tables, TanStack Query for API calls
- React Hook Form + Zod for forms and validation
- next-intl for Khmer (default) and English
- lucide-react icons
- Font: Kantumruy Pro for Khmer with an English fallback; use enough line height
  so Khmer subscripts and vowels are never clipped in buttons, inputs or table cells
- A shared app shell: collapsible sidebar, top bar with language switcher and user menu

Requirements:
- Create the web-console project. It will later also hold the dispatcher console
  and corporate portal, so set up a structure that can grow (route groups per area,
  shared API client, shared layout).
- Phone OTP login using my existing admin account. Store tokens safely
  (httpOnly cookies via a Next.js route handler, not localStorage),
  automatic refresh, logout.
- Pages:
  1. Driver list: table with filters, search, status badges, and a
     "documents expiring soon" indicator.
  2. Register driver: a step form (personal details → vehicle → documents → review)
     designed for staff sitting with a driver in person. Allow taking photos of
     documents from a phone or laptop camera.
  3. Driver detail: profile, vehicles, documents with preview, onboarding history,
     audit trail, and action buttons (approve, reject, suspend, reinstate) with a
     required reason dialog.
  4. Document review queue: all pending documents, side-by-side preview with
     approve/reject.
  5. Vehicles list: plate, class, owner, assigned driver, status.
- Role-based navigation: only show pages the logged-in user's roles allow.
- Clean, dense design for an operations tool; must also work on a tablet.
- Add the web-console to docker-compose and to the CI workflow (lint, type-check, build).

Before writing code: report the results of Part A Step 1, then give me your plan
(endpoints to add or change, and the page structure), and wait for my approval.
```

---

## Prompt 2.6 — Update CLAUDE.md with frontend conventions

Before Prompt 3: register one real driver end-to-end in the console (create, vehicle,
documents, approve), check that Khmer text displays properly, and commit.

```
Update CLAUDE.md: add the web-console frontend stack and conventions we used in
Prompt 2.5 (shadcn/ui, TanStack Table/Query, React Hook Form + Zod, next-intl,
Kantumruy Pro, httpOnly cookie auth, route groups per area). Keep it short.
```

---

## Prompt 3 — Corporate accounts and places (+ admin screens)

```
Read CLAUDE.md. Prompts 0, 1, 2 and 2.5 are done. Implement the corporate and place
modules, plus their admin screens in the existing web-console.

Part A — backend (core-app):

Corporate:
- corporate.corporate_accounts (legal_name, tax_id, payment_terms_days,
  credit_limit_minor, currency, service_fee_pct default 8.00, status),
  corporate.cost_centers, corporate.corporate_members with a JSONB policy
  (allowed vehicle classes, allowed hours, max fare).
- Platform ADMIN endpoints: create/edit corporate accounts, add the first
  CORPORATE_ADMIN for a client (reuse existing user if the phone exists),
  suspend/reactivate an account. All audit-logged.
- CORPORATE_ADMIN endpoints: manage members, cost centres and policies for their own
  company only (enforce scope_id).
- A PolicyChecker that the booking module will call later to validate a ride
  against the member's policy. Unit-test it.

Place (landmark database):
- place.places with name_km, name_en, PostGIS point, place_type, pickup_notes,
  usage_count, and a verified flag.
- GET /api/v1/places/search?q= using pg_trgm similarity on Khmer and English names,
  optionally biased by distance to a lat/lng.
- Saved places per user (Home, Office, custom label).
- ADMIN endpoints to create, edit, merge duplicates and verify places.
- Seed data: Techo International Airport and ~20 well-known Phnom Penh landmarks,
  marked verified = false because coordinates are approximate.

Part B — web-console (extend the existing project; reuse its layout, API client,
auth and i18n):
- Corporate accounts: list, create/edit, account detail with members,
  cost centres and policy editor, suspend/reactivate with reason.
- Places: searchable list with an "unverified" filter, and a place editor with a
  map (MapLibre GL + OpenStreetMap tiles) where I can drag the pin to the correct
  location, edit Khmer/English names and pickup notes, and mark it verified.
- Add the new pages to the sidebar with role-based visibility.

Tests for all new endpoints, including corporate scope isolation (a corporate admin
must never see another company's data).

Plan first: list the endpoints and pages, then wait for my approval.
```

---

## Prompt 4 — Pricing and fare quotes (+ admin screens)

```
Read CLAUDE.md. Implement the pricing module, plus its admin screens in the existing
web-console.

Part A — backend (core-app):

- pricing.pricing_rules: city, vehicle_class, base, per_km, per_min, min_fare,
  fuel_index_factor, max_surge, optional corporate_id override, validity period, currency.
- pricing.zones with PostGIS polygons; an AIRPORT zone adds a fixed surcharge.
- A RoutingClient interface returning distance and duration. Provide an OSRM HTTP
  implementation and a straight-line fallback for tests.
- POST /api/v1/quotes: returns quoteId, total (minor units) with KHR equivalent,
  a breakdown (base, distance, time, airport, fuel index), ETA, and expiresAt.
  Quotes are immutable rows in pricing.fare_quotes.
- Exchange rate from a configurable value for now (interface so it can come from a feed later).
- Surge multiplier must never exceed max_surge.
- Unit tests for fare calculation, including corporate overrides and airport zone.
- ADMIN endpoints to manage pricing rules, zones, the fuel index factor and the
  exchange rate. Changes create a new rule version (never edit a rule that existing
  quotes reference). All audit-logged.

Part B — web-console (extend the existing project):
- Pricing rules: list by city and vehicle class, create a new version with a
  validity date, and view rule history.
- Corporate price overrides on the corporate account detail page.
- Zones: draw and edit zone polygons on the map (AIRPORT, CITY, RESTRICTED).
- Fuel index and exchange rate settings, showing who changed them and when.
- Quote tester: pick pickup and drop-off (place search or map), vehicle class and
  optional corporate account, and show the full quote breakdown in USD and KHR.
  I will use this to check prices before giving them to clients.

Plan first: list the endpoints and pages, then wait for my approval.
```

---

## Prompt 5 — Booking and trip lifecycle

```
Implement the booking module:

- booking.ride_requests and booking.trips and booking.trip_events as described in CLAUDE.md.
- Booking types: SCHEDULED, AIRPORT (with flight_no), RECURRING (weekday series),
  ON_DEMAND (allowed in the model but not exposed yet).
- POST /api/v1/rides: takes a quoteId, pickup notes / voice note key, payment method,
  optional corporate cost centre and rider. Validates the quote is unexpired and the
  corporate policy allows it. Requires an Idempotency-Key.
- Trip state machine: SCHEDULED → ASSIGNED → DRIVER_EN_ROUTE → DRIVER_ARRIVED →
  IN_PROGRESS → COMPLETED, plus CANCELLED_BY_PASSENGER, CANCELLED_BY_DRIVER,
  CANCELLED_BY_OPS, NO_SHOW, DISPUTED. Guarded transitions, trip_events row for each,
  optimistic locking on trips.
- Publish domain events through the outbox table: RideBooked, TripAssigned,
  TripStatusChanged, TripCompleted, TripCancelled.
- Outbox relay that publishes to RabbitMQ and marks events published.
- Driver endpoints: arrived, start, complete, report cash collected.
- Passenger/booker endpoints: get ride, list upcoming, cancel.
- Tests: full happy path, invalid transitions, concurrent assignment conflict,
  idempotent booking retry.

Plan first.
```

---

## Prompt 6 — Dispatch (manual + assisted) and at-risk detection

```
Implement the dispatch module for scheduled trips:

- Driver shift blocks: drivers declare availability windows; dispatchers can see them.
- Manual assignment: dispatcher assigns a driver and an optional backup driver to a trip.
  Every manual assignment requires a reason code and is audit-logged.
- Assignment suggestions: for a given day, suggest a driver + backup per trip using
  simple rules for now (vehicle class match, driver available in the time window,
  shortest estimated travel from the driver's previous drop-off or home zone,
  and spreading trips fairly across drivers). Keep this behind an
  AssignmentStrategy interface so I can plug in Timefold later.
- Driver confirmation: assigned drivers must confirm; unconfirmed at T-60 minutes
  creates an exception.
- At-risk evaluator: a scheduled job every 30 seconds computes
  slack = time_to_pickup − driver_ETA_to_pickup using Redis driver positions.
  Tiers: GREEN > 15 min, AMBER 5–15 min, RED < 5 min, or no location ping for 3 minutes.
- Exception queue: dispatch.exceptions table (type, trip, severity, status,
  assigned dispatcher), sorted by severity then pickup time.
- One-click "switch to backup" action with optimistic locking.
- Endpoints for the dispatcher console: exception queue, day timeline by driver,
  assign/reassign, resolve exception.
- Tests for slack tiers, confirmation deadline, and reassignment conflicts.

Plan first.
```

---

## Rules for every prompt from here on

These are already in the project; each prompt below repeats the key points so Claude Code
can't miss them:
- api-gateway (Spring Cloud Gateway) is the single public API host and checks tokens once.
- Every service verifies tokens again through the shared **platform-security** library,
  using core-app's JWKS (`/.well-known/jwks.json`, internal only). Only core-app holds
  signing keys. New services must use platform-security and must not copy token code.
- The console's BFF calls the gateway, never services directly.
- Every public unauthenticated route must be listed explicitly in the gateway
  configuration with a reason, and the service behind it must verify the caller itself.
- Rate limits arrive in gateway phase 3; partner API keys in gateway phase 4.

---

## Prompt 7 — Location service (realtime, through the gateway)

```
Read CLAUDE.md and the latest docs/handover-*.md first.

Implement location-service as a separate Spring Boot app:

Security and routing:
- Use the platform-security library to verify tokens via core-app's JWKS.
  Do not copy any token code. location-service holds no signing keys.
- All traffic goes through the api-gateway, including WebSockets. Add the gateway routes
  (HTTP and WebSocket) and test that location-service is not reachable directly
  from outside in the local stack.
- WebSocket authentication: browsers cannot send an Authorization header on the
  handshake, and the console keeps tokens in httpOnly cookies behind the BFF.
  Propose a design and wait for my approval. My preferred option: the BFF requests a
  short-lived, single-use WebSocket ticket from core-app (bound to the user, expiring
  in ~30 seconds), the browser connects through the gateway with that ticket, and the
  ticket is validated and consumed on connect. The driver app may pass its access
  token in the handshake instead. Explain the trade-offs of the alternatives.
- Handle token expiry on long-lived sockets (close with a clear code so clients
  reconnect with a fresh token or ticket).

Functionality:
- Drivers send location pings {lat, lng, speed, heading, accuracy, ts} every ~4s on trip,
  ~15s idle. Also accept an HTTP batch endpoint (through the gateway) for when the
  socket drops.
- Write latest position to Redis GEO + a driver state hash (last_seen, status, trip_id).
- Publish trip-scoped updates via Redis pub/sub so passengers, dispatchers and the
  public share link receive live updates on their own WebSocket subscriptions.
- Authorization on subscriptions: a passenger only sees their own trips, a dispatcher
  sees all, a corporate user only their company's trips.
- Batch-persist breadcrumbs to a daily-partitioned booking.location_pings table
  (create partitions automatically; retention 90 days). Write through a module API
  or event, not directly into another module's tables.
- Public tracking: GET /public/track/{shareToken} + a socket topic for that token,
  exposing only driver first name, vehicle, plate, position and ETA.
  This is a public unauthenticated gateway route: list it explicitly with a reason.
  Share tokens must be long and random (at least 128 bits), expire when the trip
  ends plus a short grace period, and be revocable. Rate limiting comes in gateway
  phase 3; note in the handover doc that this route must not go live before then.
- Basic GPS noise filtering (drop pings with poor accuracy or impossible speed).
- Load-test script (k6 or Gatling) simulating 500 drivers through the gateway, and
  report gateway latency and resource use.

Add location-service to scripts/dev.sh (stack up and smoke test) and scripts/check.sh.
Finish by running scripts/check.sh and writing the handover doc.

Plan first.
```

---

## Prompt 8 — Dispatcher console and corporate portal

```
Read CLAUDE.md and the latest docs/handover-*.md first.

Extend the existing web-console; reuse its login, layout, API client, i18n setup and
map component. Do not create a new project. The admin pages for drivers, corporate
accounts, places and pricing already exist.

Security and routing:
- The browser only talks to the BFF; the BFF calls the api-gateway. No direct calls
  from the browser to services, and no tokens in browser JavaScript.
- Live updates use the WebSocket ticket flow approved in Prompt 7
  (BFF gets a ticket, browser connects through the gateway). Handle reconnects
  and ticket expiry gracefully.

Dispatcher pages (DISPATCHER role):
  1. Exception queue (main screen): sorted by risk, colour-coded tiers,
     actions: call driver (tel: link), switch to backup, reassign, resolve.
  2. Day timeline: drivers as rows, trips as blocks, drag or select to reassign.
  3. Trip detail: event history, live map, contact details, notes.
  4. Tomorrow planning view: suggested assignments to approve or change,
     confirmation status per driver.
- New exceptions should be noticeable even when the dispatcher is on another page
  (badge in the sidebar and an optional sound).

Corporate portal pages (CORPORATE_ADMIN and CORPORATE_BOOKER roles, own company only):
- Book a ride for a member (place search, time, vehicle, flight number, notes,
  fixed quote, confirm), upcoming trips, trip history, spend by cost centre.
- Invoices page as a placeholder until Prompt 10.
- Hotels will book through this portal for now (no partner API until gateway phase 4).

- Keep the dispatcher screens dense and usable on a laptop; the corporate portal
  should be simpler and work well on a phone.
- Role-based navigation: each role sees only its own area.
- Extend the scripts/dev.sh smoke test with a dispatcher and a corporate login.

Finish by running scripts/check.sh and writing the handover doc.

Plan first, including the page list and component structure.
```

---

## Prompt 9 — Telegram bot (public webhook route)

```
Read CLAUDE.md and the latest docs/handover-*.md first.

Add a Telegram bot integration (in core-app, notification + booking modules):

Security and routing:
- The Telegram webhook is a public unauthenticated route through the api-gateway.
  List it explicitly in the gateway configuration with a reason; no JWT on this route.
- core-app must verify Telegram's secret-token header on every request (constant-time
  comparison) and reject anything else. Store the bot token and secret in environment
  variables; never log them.
- Bot actions run as the linked user: after verifying the webhook, resolve the
  Telegram account to our user and apply the same role and scope checks as the
  REST API (a booker only books for their own company, a driver only acts on
  their own trips). Do not bypass authorization because the request came from Telegram.
- Rate limiting comes in gateway phase 3; note in the handover doc that this route
  must not go live before then. Also add per-chat throttling inside the bot.

Functionality:
- Corporate bookers and hotel staff can book: pickup, drop-off (search places),
  time, vehicle, flight number, notes → show fixed quote → confirm.
- Drivers receive assignment messages in Khmer with pickup landmark, notes, fare,
  payment type, and inline buttons: Confirm / Can't do it.
- Drivers can tap buttons for Arrived / Started / Completed as a fallback to the app.
- Link Telegram accounts to users by phone number verification.
- Make webhook handling idempotent (Telegram retries updates; use update_id).
- All messages from message bundles (km/en).

Add a Telegram webhook simulation to the scripts/dev.sh smoke test.
Finish by running scripts/check.sh and writing the handover doc.

Plan first.
```

---

## Prompt 10 — Payments, ledger, payouts, invoices (public webhook route)

```
Read CLAUDE.md and the latest docs/handover-*.md first.

Implement the payment module:

Security and routing:
- The KHQR payment webhook is a public unauthenticated route through the api-gateway.
  List it explicitly in the gateway configuration with a reason; no JWT on this route.
- core-app must verify the payment provider's signature on every webhook, reject
  stale or replayed requests (timestamp + provider_ref), and never trust amounts
  from the webhook without checking them against our own payment record.
  Leave a place for an IP allowlist at the gateway once the provider is chosen.
- Rate limiting comes in gateway phase 3; note in the handover doc that this route
  must not go live before then.
- Admin finance endpoints (payouts, invoices, ageing) go through the gateway with the
  normal JWT and ADMIN role, verified via platform-security.

Functionality:
- Double-entry ledger: payment.ledger_accounts and payment.ledger_entries.
  Every transaction nets to zero; add a test that verifies this invariant.
- On TripCompleted: post entries for fare, driver commission (default 12%, max 15%),
  corporate service fee, and driver earnings. Create driver_earnings rows.
- Cash trips: platform fee becomes a driver receivable, netted from the next payout.
- KHQR: a PaymentGateway interface (dynamic QR per trip, webhook confirmation,
  idempotent by provider_ref). Provide a fake gateway implementation for dev,
  including a fake signed webhook so the full flow can be tested locally.
- Weekly driver payouts: calculate, create payout batch, export CSV for bank transfer,
  and a per-driver statement (trips, commission, net).
- Monthly corporate invoices: per client and cost centre, service fee, totals,
  status, due date; PDF generation.
- Receivables ageing report for admins.
- Replace the invoices placeholder page in the corporate portal and add admin finance
  pages (payouts, invoices, ageing) to the web-console.
- Tests for commission cap, cash netting, webhook signature and replay rejection,
  webhook idempotency and invoice totals.

Add a fake payment webhook to the scripts/dev.sh smoke test.
Finish by running scripts/check.sh and writing the handover doc.

Plan first.
```

---

## Prompt 11 — Notifications, safety and audit

```
Read CLAUDE.md and the latest docs/handover-*.md first.

Implement notification, safety and audit:

Security and routing:
- If the notification worker becomes a separate service, it must use platform-security
  for any HTTP endpoints (for example, admin template management) and be reachable
  only through the api-gateway. Its RabbitMQ consumers do not need JWTs.
  Recommend whether it should be separate or stay in core-app, with reasons.
- SOS and incident endpoints go through the gateway with the normal JWT.
  The SOS endpoint must keep working when other parts of the system are degraded:
  make sure it has no unnecessary dependencies on the request path.
- Rate limits (gateway phase 3) must never block SOS requests: note this in the
  handover doc so the phase 3 configuration exempts or separately limits it.

Notifications:
- A worker consuming domain events and sending via channel interfaces:
  push (FCM), SMS, Telegram. Per-user channel preferences, km/en templates,
  retry with backoff, and a notifications log table.

Safety:
- POST /api/v1/safety/sos creates a safety incident, notifies all on-duty
  dispatchers and safety officers on every channel, and appears at the top of the
  exception queue. Track acknowledged_at; alert again if not acknowledged in 60 seconds.
- Route deviation detection using trip breadcrumbs vs expected route.
- Share-trip link creation for passengers (reuse the share tokens from Prompt 7).
- Incident workflow: OPEN → ACKNOWLEDGED → ESCALATED → RESOLVED.

Audit:
- A consistent way (annotation or event listener) to write audit_logs for staff
  actions and for any access to encrypted personal data.
- Record the authenticated user and roles from the verified token
  (via platform-security), plus the gateway's request/trace ID.
- Monthly partitioned audit_logs table.

Add an SOS flow to the scripts/dev.sh smoke test.
Finish by running scripts/check.sh and writing the handover doc.

Plan first.
```

---

## Prompt 12 — Production readiness

```
Read CLAUDE.md and the latest docs/handover-*.md first.

Prepare the system for a pilot deployment:

Gateway and security:
- Include the api-gateway in the deployment as the only public entry point.
  Services must not be publicly reachable.
- A test (in CI and as a post-deploy check) that /.well-known/jwks.json is NOT
  reachable from outside, while services can still fetch it internally.
- Signing key rotation procedure: key IDs (kid), publishing the new key before
  signing with it, an overlap period, removing the old key, and how long services
  and the gateway cache the JWKS. Write it as a runbook and test it locally.
- List every public unauthenticated gateway route with its reason and its own
  protection (signature, secret, share token). Confirm gateway phase 3 rate limits
  are in place before go-live, with SOS exempted or separately limited.
- Confirm no service other than core-app holds signing keys, and no service
  contains copied token code instead of platform-security.
- Security review checklist: OWASP top 10, secrets handling, admin MFA,
  personal data encryption.

Observability:
- OpenTelemetry tracing with trace propagation from gateway → BFF → services,
  Prometheus metrics, structured JSON logging with the trace ID in every log line.
- Business metrics: on-time pickup rate, exceptions per day, SOS acknowledgement time,
  trips per dispatcher shift.
- Gateway metrics: latency, error rates by route, WebSocket connection counts.

Deployment:
- Dockerfiles (multi-stage, non-root) for api-gateway, core-app, location-service,
  web-console, and any other services.
- Kubernetes manifests or Helm chart for a small managed cluster; alternatively
  explain a simpler managed-container option and recommend one.
- Run scripts/check.sh in CI, and use the scripts/dev.sh smoke test as a
  post-deploy smoke test against the pilot environment.
- Database backup and restore procedure.
- Seed script with realistic demo data for a pilot demo.
- Map tiles: replace the public OpenStreetMap tile servers (fine for development,
  not allowed for heavy production use) with a hosted tile provider or self-hosted
  tiles. Recommend options with rough monthly cost.

Finish with a go-live checklist and a handover doc.

Plan first, and list anything that still needs a decision from me.
```

---

## Useful follow-up prompts

- `Review the code you wrote in the booking module for bugs, race conditions and missing tests.`
- `Explain how a trip moves from booking to payout, file by file.`
- `Check that no module accesses another module's tables directly. Fix any violations.`
- `Write an ADR (architecture decision record) explaining why we chose a modular monolith.`
- `Update CLAUDE.md with anything important you learned during this step.`
- `Check that every service verifies tokens through platform-security and that no token code has been copied. Fix any violations.`
- `List every public unauthenticated route in the gateway, with its reason and protection. Flag anything missing.`
