# Local booking and dispatch fixtures

Seeded and verified on 2026-11-11 against this repository's local Compose database.

## Accounts

| Purpose | Login phone | Driver ID / vehicle |
| --- | --- | --- |
| Driver candidate 1 | `010555801` (`+85510555801`) | `ea8e0cf8-4554-44c3-a569-3685f9cf7fe5` / `LOCAL-SEED-010555801` |
| Driver candidate 2 | `010555802` (`+85510555802`) | `5e483106-47a6-48be-82c1-7c8ccc33641a` / `LOCAL-SEED-010555802` |
| Passenger | `010555803` (`+85510555803`) | Local Booking Passenger |

Both drivers are APPROVED, have active CAR vehicles, and each has four approved unexpired synthetic
fixture documents. Document images explicitly say they are not real documents. Neither driver has an
ADMIN role. Either can be selected as primary; the other can be an airport/corporate backup.
Use your existing local OTP login configuration; the seed does not change OTP settings or print tokens.

## Coverage and shifts

- Fourteen active shift blocks: each driver works **06:00–20:00 Asia/Phnom_Penh**, November 12–18, 2026.
- Verified zero overlapping shift pairs for these drivers.
- Repeat runs add missing shifts for the next seven local calendar days and preserve overlapping existing blocks.
- Existing PHNOM_PENH city/airport development zones and all six vehicle-class pricing lines were preserved.
- City pickup example: `11.5564, 104.9282` (verified inside the local CITY zone).
- KTI pickup example: `11.3633, 104.9211` (verified inside the local AIRPORT zone).
- KTI fixture landmark UUID: `7ca0c1ad-7f1e-40e5-a371-aef081d2d017`.
- These airport coordinates and zone rectangles are approximate test fixtures, not surveyed production boundaries.

For a scheduled CAR test, use November 12 at 09:00 Phnom Penh (`2026-11-12T02:00:00Z`) while it remains
in the future. For later test dates, choose a future pickup within seeded shifts and allow time for the
entire trip. Create a fresh quote; quote expiration still applies.

## Repeat the seed

```bash
./scripts/seed-booking.sh
```

The script:

1. Uses `.env` credentials but forces backend connections to loopback local infrastructure.
2. Refuses driver fixture phone collisions with differently named accounts.
3. Builds the current core-app and launches a temporary local server on an ephemeral loopback port for
   each dedicated driver. The existing local-only driver seeder uses normal onboarding, document approval
   and vehicle services. `LOCAL_APPROVED_DRIVER_SEED_PHONE` selects the fixture; its original default remains
   `012898339` for existing users of that seeder.
4. Applies Flyway migrations at startup, including V18 (passenger locations) and V19 (assignment jobs).
5. Stops only the temporary server it started. Scheduled jobs, automatic assignment, its Rabbit listener
   and admin bootstrap are disabled in that temporary process.
6. Runs the existing non-overwriting pricing seed, then the passenger/shift fixture SQL with an advisory lock.
7. Prints driver readiness and migration summaries. Logs are in `var/seed-booking/`.

Existing IDE-run core-app, location-service and gateway processes are not stopped or restarted.
No quote, ride, trip booking, cash collection or live GPS data is created. No existing personal account is
promoted or edited by the dedicated fixture path. Existing pricing/settings are not overwritten.

## Enable automatic assignment for testing

Your running IDE process may still contain the old classes. Restart **core-app from the current source**
with the following environment setting in its local run configuration:

```bash
DISPATCH_AUTOMATIC_ENABLED=true
```

Keep the local profile and existing environment settings. The seed itself leaves this flag and `.env`
unchanged. ON_DEMAND and METERED rollout flags also remain unchanged; the automatic planner handles
scheduled/recurring/airport quoted rides. Use the guide below to create a booking and inspect the flow.

## Validation

- Seed ran twice successfully; still two dedicated drivers, two dedicated vehicles, four documents each,
  one dedicated passenger and fourteen future shifts.
- Both drivers' document readiness and vehicle class were verified against the local database.
- V18 and V19 applied successfully; service coverage checks passed for both listed points.
- `LocalApprovedDriverSeedTest` and `LocalApprovedDriverSeedIT` passed, including custom-phone creation,
  malformed-phone rejection and repeatability.
- No commit, push, deployment or real passenger booking was performed.

[Booking flow and command guide](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/booking-flow-java-spring-guide.md)

## Role-specific setup guides

- [Passenger setup and booking test](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/passenger-local-setup.md)
- [Driver setup and trip execution test](/Users/kosalpen/Documents/scheduled-mobility-backend/scheduled-mobility/docs/driver-local-setup.md)
