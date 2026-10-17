# Handover: Prompt 3 — corporate and place modules (+ admin screens)

Written 2026-10-13, updated 2026-10-17. For a new Claude Code session that needs Prompt 3's context.
Status: **done.** Plan approved 2026-10-13 (all recommendations; MapLibre + OSM, no satellite layer);
implemented, tested and committed (`486338b`, `8f76807`, `6ae19ee`, `74fc0b7`) and running on staging.
Read this file and `CLAUDE.md`; open follow-ups are in section 9.

---

## 1. Where the project stands

- Repo `/Users/channrith.moul/code/mobility`, GitHub `channrith/scheduled-mobility`, branch `main`.
- Done: Prompt 0 (skeleton), 1 (identity), 2 (driver), 2.5 (admin web console for driver onboarding).
  A separate session handled staging deployment (see `docs/staging.md`, `docs/handover-deployment.md`);
  its recent commits: `8c3541b` pre-deploy dump + migration-aware rollback, `5f72238` web console on
  staging, `1ec32b0` API docs off by default. Check `git log` for anything newer.
- `CLAUDE.md` has a "Web console" section (stack and conventions), committed in `3b340f7`.
- Migrations: V1–V6 before Prompt 3; Prompt 3 added V7 (corporate), V8 (place), V9 (seed landmarks,
  all `verified=false`).
- At the end of Prompt 3: 141 unit, 206 integration and 2 location-service tests passing.

## 2. The request (Prompt 3, summarized)

**Backend.** Corporate: `corporate_accounts` (legal_name, tax_id, payment_terms_days, credit_limit_minor,
currency, service_fee_pct default 8.00, status), `cost_centers`, `corporate_members` with a JSONB policy
(allowed vehicle classes, allowed hours, max fare). Platform-ADMIN endpoints: create/edit accounts, add the
first CORPORATE_ADMIN (reuse an existing user by phone), suspend/reactivate — all audited.
CORPORATE_ADMIN endpoints: members, cost centres and policies for their own company only. A
`PolicyChecker` for booking to call later, unit-tested.
Place: `places` (name_km, name_en, PostGIS point, place_type, pickup_notes, usage_count, verified);
`GET /api/v1/places/search?q=` with pg_trgm on Khmer and English, optional distance bias; saved places per
user (Home, Office, custom); ADMIN create/edit/merge/verify; seed Techo International Airport and ~20
Phnom Penh landmarks with `verified = false`.
**Web console.** Corporate accounts (list, create/edit, detail with members, cost centres, policy editor,
suspend/reactivate with reason); places (searchable list with "unverified" filter, editor with MapLibre GL
+ OpenStreetMap map and draggable pin, km/en names, pickup notes, verify); sidebar entries by role.
**Tests** for every new endpoint, including corporate scope isolation.

## 3. Findings already established

- **pg_trgm works on Khmer** with the database locale `en_US.utf8` (postgis image, same on staging):
  `similarity('អាកាសយានដ្ឋានតេជោ','អាកាសយានដ្ឋាន') = 0.68`, `word_similarity('អាកាស', …) = 0.83`. Use
  `word_similarity` for partial Khmer queries (Khmer has no spaces between words).
- `UserAccounts.ensureUserWithRole` **refuses corporate roles** today (they need a scope). Needs a scoped
  grant/revoke API.
- `Authz.canManageCorporate(id)` (platform ADMIN or that company's CORPORATE_ADMIN) and
  `canAccessCorporate(id)` (staff or any corporate role there) already exist; tokens carry a `corp` claim
  `{corporateId: [roles]}`. New grants appear in the token at the next refresh (≤ 15 min).
- The web console login only admits staff roles (`STAFF_ROLES` in `web-console/src/lib/auth/roles.ts`,
  checked in `src/app/api/auth/otp/verify/route.ts`).
- `VehicleClass` is public API of the driver module (`com.mobility.core.driver.VehicleClass`); corporate
  may depend on it.

## 4. Proposed plan (awaiting approval)

### 4.1 Corporate (`V7__corporate.sql`)
Tables
- `corporate_accounts`: legal_name, display_name, tax_id (unique), payment_terms_days (0–120, default 30),
  credit_limit_minor BIGINT + currency CHAR(3) (USD/KHR), service_fee_pct NUMERIC(5,2) (default 8.00,
  0–100), status ACTIVE/SUSPENDED + suspension reason/by/at, created/updated, version.
- `cost_centers`: account_id, code (unique per account), name, active.
- `corporate_members`: account_id, user_id (by value, no FK into identity), role, cost_center_id,
  employee_ref, policy JSONB, status ACTIVE/REMOVED, unique (account_id, user_id), version.

Policy JSON (validated server-side; a missing field means "no restriction"):
`{allowedVehicleClasses:[...], allowedHours:[{days:[MON..SUN], from:"07:00", to:"20:00"}],
maxFare:{amountMinor, currency}}`. Windows may cross midnight (22:00–06:00). Hours in Asia/Phnom_Penh.

Identity sync: adding a member grants the identity role scoped to the account; removing revokes it. New
identity public API `UserAccounts.grantScopedRole(...)` / `revokeScopedRole(...)` (audited, reuses the
existing user by phone).

Platform ADMIN — `/api/v1/admin/corporates` (all audited)
| Endpoint | |
|---|---|
| `POST /` · `GET /?q&status&page` · `GET /{id}` · `PUT /{id}` (`version`) | accounts |
| `POST /{id}/admins` `{phone, fullName}` | add (first or another) CORPORATE_ADMIN, reuse existing user |
| `POST /{id}/suspend` · `POST /{id}/reactivate` `{reason}` | reason ≥ 10 chars, records who/when |

Company — `/api/v1/corporates/{corporateId}/…`, `@PreAuthorize("@authz.canManageCorporate(#corporateId)")`
(platform ADMIN or that company's CORPORATE_ADMIN), all audited
| Endpoint | |
|---|---|
| `GET /` | own account summary (read-only) |
| `GET/POST /members` · `PUT/DELETE /members/{memberId}` | role, cost centre, policy, remove |
| `GET/POST /cost-centers` · `PUT /cost-centers/{id}` | rename, deactivate |

Scope isolation, enforced twice: (1) token scope on `corporateId`; (2) every member/cost-centre lookup also
filters by `corporateId` (`/corporates/A/members/{id of B}` → 404). SUSPENDED accounts: company endpoints
read-only (`409 corporate.suspended`).

`PolicyChecker` (corporate public API, called by booking later):
`check(corporateId, memberUserId, RideRequest{vehicleClass, pickupAt, fareMinor, currency})` → allowed +
violations: `ACCOUNT_SUSPENDED`, `NOT_A_MEMBER`/`MEMBER_REMOVED`, `VEHICLE_CLASS_NOT_ALLOWED`,
`OUTSIDE_ALLOWED_HOURS`, `FARE_ABOVE_LIMIT`, `CURRENCY_MISMATCH`. Pure unit tests (midnight windows, edge
minutes, missing fields).

### 4.2 Place (`V8__place.sql`, `V9__place_seed.sql`)
- `place.places`: name_km, name_en, latitude, longitude + **generated** `location geography(Point,4326)`
  (`ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography`, GiST index), place_type (AIRPORT, HOTEL, MALL,
  MARKET, HOSPITAL, SCHOOL, EMBASSY, OFFICE, LANDMARK, TRANSPORT, OTHER), pickup_notes_km,
  pickup_notes_en, usage_count, verified, status ACTIVE/MERGED + merged_into, version. Trigram GIN indexes
  on both names (`lower(name_en)`).
- Search `GET /api/v1/places/search?q=&lat=&lng=&limit=` (any authenticated user): word_similarity/similarity
  on both names + prefix matches; with lat/lng: score = 0.7 × text + 0.3 × proximity (decays over a few
  km); merged excluded; results include distance.
- `place.saved_places`: user_id, label HOME/OFFICE/CUSTOM + custom_label (HOME/OFFICE unique per user);
  either `place_id` or own pin + free-text note. `GET/POST/PUT/DELETE /api/v1/me/saved-places[/{id}]`,
  own records only.
- Admin `/api/v1/admin/places` (audited): `GET /?q&verified&type&page`, `GET /{id}`, `POST /`,
  `PUT /{id}` (`version`; names, pin, notes, type, verified), `POST /{id}/merge {intoPlaceId}` (source →
  MERGED, saved places moved, usage counts added).
- Public API for booking later: `PlaceDirectory.get(id)`, `recordUsage(id)`.
- Seed (versioned migration, all `verified = false`, approximate coordinates): Techo International
  Airport, Royal Palace, Wat Phnom, Independence Monument, Central Market (Phsar Thmei), Russian Market
  (Toul Tom Poung), AEON Mall 1, AEON Mall 2 (Sen Sok), Olympic Stadium, Calmette Hospital, Royal
  University of Phnom Penh, Chroy Changvar Bridge, Vattanac Capital, Night Market, Sorya Mall, US Embassy,
  Phnom Penh Autonomous Port, Koh Pich (Diamond Island), Tuol Sleng Genocide Museum, and one or two more.

### 4.3 Tests
ITs for every endpoint (roles, audit rows, versions, validation). **Corporate isolation**: A's admin gets
403 on B's URLs and 404 on B's ids under A's URL; B's data never in A's lists. Identity role
granted/revoked with membership. Place search: Khmer, English, partial, misspelled, distance bias, merged
excluded; merge moves saved places. PolicyChecker unit tests. Regenerate `docs/openapi.json`
(`./mvnw verify -Dopenapi.update=true`) and console types (`npm run gen:api`).

### 4.4 Web console
| Route | Roles | Content |
|---|---|---|
| `/corporates` | ADMIN edit; SUPPORT, DISPATCHER read | list, search, status filter |
| `/corporates/new` | ADMIN | create + "add first company admin" |
| `/corporates/[id]` | as above | tabs: overview/edit · members (add, role, cost centre, policy editor dialog) · cost centres · suspend/reactivate with reason |
| `/places` | ADMIN, DISPATCHER | search list, "unverified" and type filters |
| `/places/new`, `/places/[id]` | ADMIN, DISPATCHER edit; ADMIN merge | MapLibre GL + OSM, draggable pin, km/en names, type, pickup notes, verify, merge (search picker) |

Sidebar: "Corporate clients" and "Places" with role-based visibility (extend `permissions` in
`src/lib/auth/roles.ts` and `components/shell/nav.ts`). New dependency: **`maplibre-gl`** (no React
wrapper; load client-side only; show OSM attribution).

## 5. Decisions

### Approved 2026-10-13 (the user accepted all recommendations below; 7 = not now)
1. **Member roles.** Rec: members are `CORPORATE_ADMIN`, `CORPORATE_BOOKER` or `RIDER`; RIDER keeps the
   normal PASSENGER login (no new identity role); the policy applies to the person riding.
2. **Corporate admins in the console.** Rec: **not in this prompt**; build/test their API now and make the
   screens reusable components. Later: a corporate portal (see 5.2).
3. **Fare currency.** Rec: PolicyChecker compares within one currency, else `CURRENCY_MISMATCH`; pricing
   converts before calling.
4. **Storing the point.** Rec: lat/lng columns + generated PostGIS geography column, queried with SQL (no
   new libraries). Alternative: hibernate-spatial + JTS.
5. **Place editing roles.** Rec: ADMIN + DISPATCHER create/edit/verify; merge ADMIN only. (The prompt says
   "ADMIN endpoints".)
6. **OSM tiles.** Rec: public OSM tiles for the admin console now; pick a provider before public maps.
7. **Satellite layer (optional add-on).** Asked whether to add it to Prompt 3; needs a key from a provider
   whose terms allow verification work (e.g. MapTiler, Esri). **Not now.**

### Settled in discussion (don't reopen)
- **Fixed OTP on staging** (`OTP_FIXED_CODE`, value only in the droplet's `.env`; never write it in the repo, which is public) stays until the user says otherwise; don't change
  anything SMS/OTP-related. Still flag the risk where relevant.
- **Keep `AdminBootstrap`** (env-driven); no seeds for users.
- **Corporate portal vs staff console:** share the code, separate at the boundary. Same `web-console`
  project, separate route groups `(admin)` / `(corporate)`; deploy the same image twice
  (`console.<domain>` staff-only, `portal.<domain>` corporate-only), each enabling only its area, with
  separate cookies per host. Reasons: different trust levels (customers vs staff with access to all
  drivers and ID scans), the staff console may later need IP/VPN/SSO restrictions, different product UX.
- **Maps:** MapLibre + OpenStreetMap for the place editor. Google Maps was considered and **not chosen
  here** because (a) its terms generally restrict storing/caching Google content and building your own
  database from it, while this screen exists to build our own landmark database (not legal advice —
  check current terms); (b) per-load/per-call cost at ride-platform scale; (c) lock-in (own SDK, not
  MapLibre); (d) the verified landmark DB is a product asset. Google may still be used later where its
  terms fit (ETAs/Directions, a passenger search fallback, deep links into the Google Maps app for
  driver navigation), each decided with real costs.
- **OSM tiles explained:** OSM data is free (ODbL, attribution "© OpenStreetMap contributors"); the public
  `tile.openstreetmap.org` servers are donation-funded with a usage policy (light use, no SLA, no bulk
  download, visible attribution). Fine for a few staff; not for passenger/driver apps. Later options:
  hosted provider (MapTiler, Stadia Maps, Thunderforest) or self-hosted vector tiles (Protomaps PMTiles
  for Cambodia in DigitalOcean Spaces). Switching is mostly a tile-URL change.

## 6. How the user works

- `CLAUDE.md` rules: propose a plan and wait for approval; small steps; run tests after each step and
  report; suggest a commit message after each step; ask before adding libraries; ask when requirements
  are unclear.
- Droplet/GitHub operations: the user runs the commands and pastes output; Claude guides and verifies.
- The user often answers "approve" to accept all recommendations.

## 7. Codebase patterns to reuse (don't reinvent)

Backend (`core-app/src/main/java/com/mobility/core/…`)
- Errors: throw `shared.web.ApiException(status, "code", args…)`, `.withProperty(...)` for extra problem
  fields; messages in `src/main/resources/i18n/<module>.properties` (km, default) and `_en.properties`;
  add the new bundles to `spring.messages.basename` in `application.yml`.
- Audit: `audit.AuditLog.record(action, targetType, targetId, details)` — must run inside the caller's
  transaction (MANDATORY); never put PII or free text in details. Read API: `audit.AuditTrail`.
- Authorization: `@PreAuthorize("hasRole('ADMIN')")`, `@authz.canManageCorporate(#id)`; current user via
  `identity.CurrentUserProvider`; names via `identity.UserDirectory`.
- Users: `identity.UserAccounts` (`ensureUserWithRole`, `updateProfile`); phone parsing in
  `shared.phone.PhoneNumbers`.
- Paging: `shared.web.PageResponse`. Time: inject `Clock`; business dates via `shared.time.BusinessTime`.
- PII: `@Convert(converter = EncryptedString.class)` + blind index via `PiiCrypto.blindIndex`.
- Optimistic locking: `@Version` + client-sent `version`; do **all uniqueness queries before mutating**
  (Hibernate auto-flushes before queries, bumping the version twice otherwise).
- Assigned UUID ids: implement `Persistable` (or use `@Version`) so `save()` inserts instead of merging.
- OpenAPI: controllers get `@Tag`/`@Operation`; endpoint-specific errors via `@ProblemResponse`; snapshot
  `docs/openapi.json` checked by `OpenApiSnapshotIT`.
- Module rules: public API in the module base package only; Spring Modulith `ModularityTests` must pass.

Tests (`core-app/src/test/java/com/mobility/core/…`)
- `@IntegrationTest` (shared context + Testcontainers), `SignedTokens.forUser/withRoles/corporate(...)`,
  `SamplePhones.next()`, `AuthTestClient`, `CapturedEvents`, `MutableClock` (reset in `@AfterEach`),
  `DriverApi` as the model for an HTTP test helper.
- Helper class names must not match Surefire's patterns (`Test*`, `*Test`, `*Tests`).
- In Claude's tool sandbox, Testcontainers ITs need the sandbox disabled for the Maven run (the JVM can't
  reach the containers otherwise); this doesn't affect the user's terminal or CI.

Web console (`web-console/`)
- Read `node_modules/next/dist/docs/` before using Next.js APIs (Next 16: `proxy.ts`, async `params`).
- shadcn on **Base UI**: `render={<Link …/>}` instead of `asChild`. TanStack Table **v9**:
  `useTable({ features: tableFeatures({}), columns, data })`, `table.FlexRender`.
- API: `src/lib/api/client.ts` (`api.get/post/put/patch/del/upload`, adds Idempotency-Key),
  hooks in `src/lib/api/hooks.ts` (pattern: `useDriverMutation` invalidation), types via `Model<"…">` in
  `src/lib/api/types.ts` after `npm run gen:api`.
- UI helpers: `Field`, `NativeSelect`, `ReasonDialog` (reason ≥ 10, optional notice time),
  `PageHeader`, `Pagination`, `LoadingRows`/`ErrorState`/`NotAllowed`, `useUrlState` (filters in URL),
  `useDates` (hand-made Khmer dates), `RequirePermission`/`useSession().can(...)`.
- i18n: next-intl keys **must not contain dots** (dots mean nesting); every string in km and en.
- Khmer typography: Kantumruy Pro, line-height ≥ 1.65, no fixed small heights / `overflow-hidden` on text.
- `next.config.ts` must keep the Turbopack Tailwind loader rule (`@tailwindcss/turbopack`).
- Layout: `SidebarInset`/`main` need `min-w-0` (tablet widths).
- CI runs `npm run lint`, `npm run typecheck`, `npm run build`, and checks `src/lib/api/schema.ts` is in
  sync with `docs/openapi.json`.

## 8. Pitfalls already hit (avoid repeating)

- Interface `default` methods that call a `@Transactional` method bypass the proxy — put the annotation
  on the class and implement every overload.
- Browsers have no Khmer `Intl` date data; use `useDates()`.
- Browser automation: clicks near the bottom edge of the viewport only scroll; scroll the element into
  view first.
- Assignment-style periods from a form are minute-rounded; allow a start slightly before "now".
- **OpenAPI schema names are global.** Two records with the same simple name in different modules (e.g.
  `HistoryView`) merge into one schema and the docs show the wrong fields. Prefix view records per module
  (`AccountHistoryView`, `AccountSuspensionView`, `AccountReasonRequest`).
- Hibernate validation of `CHAR(3)` columns needs `@JdbcTypeCode(SqlTypes.CHAR)` on the field.
- `List.of(...).contains(null)` throws; use `stream().anyMatch(Objects::isNull)`.
- RestTestClient: pass raw query values through `uri(b -> b.path(..).queryParam(..).build())`; a
  pre-encoded string is encoded twice (Khmer searches then find nothing).
- VS Code's Java extension can write broken classes into `target/` ("Unresolved compilation problems");
  run `./mvnw clean verify`.
- **MapLibre (v6, ESM) worker:** the bundler does not emit its worker file ("Worker failed to load"). Fixed
  in `place-map.tsx` with `setWorkerUrl(new URL("maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url).href)`.
- **OSM tiles need a Referer.** Staging's Caddy sent `Referrer-Policy: no-referrer` and OSM answered with
  "Access blocked" tiles. The console host now sends `strict-origin-when-cross-origin`; API and location
  hosts keep `no-referrer` (`deploy/staging/Caddyfile`). Works locally either way (no Caddy).
- Base UI `Button` rendering a `Link` needs `nativeButton={false}`.
- Browser automation: in dev, typing before a freshly compiled page hydrates is lost; Sonner toasts don't
  time out in an unfocused automated tab and cover the page-header buttons; Base UI checkboxes have a
  hidden native input, so click the visible box, not an accessibility ref.

## 9. Follow-ups (not started)

1. **Map tiles before wider use:** public OSM tiles are fine for a few staff only. Before passenger/driver
   apps or heavy use, switch to a hosted provider (MapTiler, Stadia Maps) or self-hosted Protomaps; it is
   a tile-URL change in `web-console/src/features/places/place-map.tsx` plus a key.
2. **Verify the seed landmarks:** all 23 are approximate (`verified=false`); e.g. the Techo airport pin is
   ~300 m off the terminal. Staff check them in `/places?unverified=true`.
3. **Corporate portal** (`(corporate)` route group, separate host) for CORPORATE_ADMIN/BOOKER, reusing the
   member, cost-centre and policy components (decision 2, section 5).
4. `PolicyChecker` is ready for booking/pricing: call it when a corporate ride is quoted/booked.
5. Optional UX: move toasts to bottom-right so they don't cover page-header actions.
