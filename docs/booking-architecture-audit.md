# Phase 1: booking architecture and compatibility audit

Audit date: 2026-11-01. Documentation only: no application, enum, schema, migration, generated API or test implementation is changed in this phase. No deployment or production capability is established by this audit.

## Evidence baseline

Backend committed revision: `59af9077fcdfe72aa5843417d175b1ee8c5f3ed8`.
iOS committed revision: `ac1b0a10a05cf76d481bd5c8dc80d82329a38ee3`.
Both working trees contain substantial uncommitted work. The passenger package is untracked relative to iOS HEAD, so HEAD alone is not an inventory of the passenger implementation. Backend HEAD can establish the pre-increment contract; the working tree describes unfinished local implementation, not deployment. Runtime staging/production configuration and applied database migrations were not inspected.

The compatibility baseline preserves these exact BookingType serialized values:

```java
public enum BookingType {
    SCHEDULED,
    AIRPORT,
    RECURRING,
    ON_DEMAND
}
```

**Divergence:** the backend working enum, V15 migration, OpenAPI snapshot and passenger `wireType` currently also contain `METERED` and `AIRPORT_METERED` from earlier local work. Those additions conflict with the required baseline. They were not introduced, reverted or repaired in this audit. Earlier implementation guides describing them are historical working-tree records, not the approved contract. No `FareModel` is present in the inspected booking request/entity/read DTO contract. Separating timing/type from fare model remains future implementation.

## Backend map and committed behavior

Paths below are relative to the backend repository.

| Concern | Evidence | Confirmed baseline |
| --- | --- | --- |
| Instructions | `CLAUDE.md`, `README.md` | Spring Modulith modular monolith; public module APIs/events; PostgreSQL schema per module; UTC storage, Phnom Penh display; integer minor-unit money; localized Problem Details |
| Domain and persistence | `booking/BookingType.java`, `booking/ride/Ride.java`, `db/migration/V12__booking.sql` | Four types; quote ID unique/non-null; destination coordinates and fare/equivalent non-null; airport flight and recurrence constraints |
| Request and controller | `booking/web/RideController.java` | POST `/api/v1/rides`; committed request requires quoteId and paymentMethod; type defaults to SCHEDULED in service; optional notes, voice key, flight, recurrence, corporate member/cost centre |
| Application rules | `booking/ride/BookingService.java` | Quote must belong to caller, be unexpired and unused; route, vehicle, time and fare copied from quote; corporate policies checked for every occurrence |
| Quote contract | `pricing/web/QuoteController.java`, `pricing/quote/QuoteService.java`, `pricing/quote/FareQuote.java` | Pickup and dropoff plus vehicle required; locations resolved by server; pickupAt optional, defaults to server now; service/restricted zones and pricing rule checked; immutable fare, route distance/duration/source, ETA and expiry |
| Pricing | `pricing/quote/FareCalculator.java` and pricing services | Server tariff, fuel, surge, airport surcharge, exchange equivalent and optional corporate fee; no mobile fare calculation |
| Authorization | `identity/security/SecurityConfiguration.java`, BookingService, `booking/web/DriverTripController.java`, `booking/web/OpsTripController.java` | Authenticated API; ride ownership/corporate visibility; foreign ride masked as 404; driver owns assigned trip; operations role guards and audit |
| Assignment and planning | `booking/trip/TripService.java`, `booking/Trips.java`, `dispatch/suggest/SuggestionService.java` | Approved driver/current eligible vehicle and version checks; route endpoints/duration used in planning; airport/corporate SLA semantics; no proof of immediate automatic matching SLA |
| Lifecycle | `booking/trip/TripStateMachine.java`, TripService, `booking/trip/Trip.java` | SCHEDULED → ASSIGNED → DRIVER_EN_ROUTE → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED; guarded exceptions/cancellation/disputes; optimistic versions and immutable event history/outbox |
| Cancellation | BookingService.cancel, TripService.cancelByRider | Rider/booker cancellation before driver arrival; per-trip or cancellable occurrences; operations cancellation also supported during trip with existing guards |
| Cash/payment | TripService.reportCash, `payment/package-info.java` | Completed CASH trip can report cash once and compare with authoritative fare; personal CASH/corporate invoice selection; KHQR booking rejected. Payment module itself is a placeholder, not verified checkout, settlement or double-entry implementation |
| Retry | `shared/idempotency/IdempotencyFilter.java`, RideRepository | Caller/method/path/key-scoped Redis response cache; identical body replay; changed body returns 422 `idempotency.key-reused`; concurrent retry 409 `idempotency.in-progress`; quote uniqueness provides extra booking protection. Redis and database are not one transaction |
| Errors | `shared/web/Problems.java`, ApiException/handler | application/problem+json; localized title/detail; stable `code` extension and optional field/context |

Source Java paths in this table are under `core-app/src/main/java/com/mobility/core/`; migration paths are under `core-app/src/main/resources/`.

### ON_DEMAND guards and timing caveat

At backend HEAD, `BookingService.book` explicitly rejects ON_DEMAND with HTTP 422 `booking.type-not-available`. BookingType's comment says “in the model, not offered yet.” V12 permits storing its name, and committed OpenAPI lists it, but neither means it is bookable. Committed controller documentation offers SCHEDULED, AIRPORT and RECURRING. Quote creation accepting an omitted pickupAt is not authorization to book ON_DEMAND.

The working tree removes that rejection for quoted immediate rides, stores quote timing via new PickupTiming/V16, and advertises ON_DEMAND in a new options endpoint. That is a local behavior change without a separate on-demand rollout gate. Destination-free paths instead use the two conflicting enum additions. Current capability discovery is therefore not the approved four-type/fare-model contract.

Committed quote policy allows a five-minute past tolerance and up to 366 days ahead. It is not a verified strict future-only scheduling rule or minimum-notice guarantee. Local BookingPolicy adds a strict future check, zero minimum notice and 366-day bound. Do not silently substitute that local policy for committed behavior. Wire time is named `pickupAt`, including trip time; introducing `scheduledAt` requires an explicit compatibility decision.

### Recurring and airport compatibility

Recurrence uses the quote's Phnom Penh local time and chosen weekdays, requires the first weekday, and permits an inclusive end date within 90 days. One trip is created per occurrence with the quote fare; corporate policy is checked per occurrence. Preserve this contract.

Committed AIRPORT supports a quote touching an airport zone at either end and a normalized flight number matching the existing airline/number regex. It does not require terminal, flight lookup or meeting point. Local `contractVersion: 2` adds airport-pickup-only and meetingPoint validation while retaining legacy airport-transfer behavior without the version. Flight tracking, delay monitoring and automatic schedule changes are not established integrations.

## Local metered work: incomplete compatibility, not a release claim

Uncommitted V15 relaxes quote/destination/fare nullability, adds meter terms/meeting point and meter sessions, expands enum constraints and adds a one-active-trip index. Ride.isMetered is based on the extra BookingType cases. Requests/responses have no explicit FareModel. This design must not be treated as the desired contract or repaired by inferring metered from missing destination.

Local MeteredPricing resolves genuine pickup/service area and tariff; MeterService accepts authenticated driver samples, checks freshness/accuracy/continuity/speed, accumulates server-side measurement and finalizes fare transactionally with completion. Missing coverage blocks completion rather than guessing. Local cash reporting refuses a pending fare. Driver transport submits samples and refreshes trip detail after a destination-free completion. These are unfinished local integration surfaces, not a certified or production-trusted meter. Device telemetry trust, calibration, continuous background/network-loss behavior and operational recovery need verification. Production default `BOOKING_METERED_ENABLED=false` gates local metered booking; tests enable it.

Local automated planning skips unknown destinations and excludes drivers holding those commitments; manual dispatch remains the intended path. This avoids fabricated endpoints but does not prove production on-demand dispatch readiness. There is no verified passenger live meter stream, bank payment finalization, or settlement workflow. Final fare and payment completion are separate concepts.

## Passenger architecture and current journey

Paths below are relative to the iOS repository.

- `codex.md` and passenger-ios skill define UIKit navigation ownership, constructor injection, Foundation domain and shared presentation. README.md, PASSENGER_README.md and DRIVER_README.md referenced by instructions are absent from this working tree; README is locally deleted. Existing `docs/` is the available passenger documentation.
- `PassengerModules/Package.swift` targets iOS 15 and exports PassengerDomain, PassengerData, PassengerPresentation, PassengerFeatureContracts and PassengerUIKit. There is no PassengerSwiftUI target/renderer. DriverModules has its own UIKit and SwiftUI implementations; do not confuse them with passenger support.
- Both feature packages currently consume DesignSystem from `SharedModules`, not driver feature code. SharedModules also owns configuration, secure storage and developer tools. NetworkCore is shared Foundation transport. No module moves are needed for this feature.
- `PassengerApp/App/PassengerCompositionRoot.swift` injects live or mock identity/mobility/places, account-scoped Keychain sessions/journal, repositories, location providers, clocks and UIKit factories. Live errors do not become fixture bookings. Mock account/history/demo booking repositories remain distinct.
- `PassengerApp/App/PassengerAppCoordinator.swift` owns the single UINavigationController. Feature factory protocols return UIViewController and typed navigation outputs. `PassengerMobilityScreenFactory` retains legacy overloads plus a typed mode entry; draft caches live in its UIKit implementation. Preserve these injection points.
- Home currently has live Ride now, Metered ride, Schedule and Airport controls; availability comes from local `/booking/options`. Saved places/My rides remain separate. Missing options route (404) falls back to scheduled/quoted airport legacy support; other errors propagate. This fallback assumes the established legacy deployment and is not runtime proof of support.
- `PassengerDomain/Mobility/PassengerBookingMode.swift` derives route/time/flight requirements from a typed UI mode with airport fare variants; separate typed metered input omits destination. However its wire mapping emits METERED/AIRPORT_METERED, so it conflicts with the approved backend enum. Quoted input still carries a String type; no explicit backend FareModel is encoded or decoded.
- `PassengerPresentation/Mobility/PassengerBookingViewModel.swift` shares validation/review/retry state, injected clock and quote expiry checks. It omits time on immediate quote requests, hides destination for meter, requires airport flight/meeting and locks uncertain submissions. Local capabilities and scheduling rules affect form validity.
- `PassengerData/Mobility/PassengerAPIMobilityRepository.swift` and DTOs implement places/saved places, quotes, booking/read/cancel and capability discovery. Quote POST uses actual endpoints; metered branch skips quotes. Exact serialized booking body plus key are journaled by account/origin for restart retries. Quoted submissions send contractVersion=2. Destination/amount read models are optional and never mapped to fake coordinates or zero fare; absence alone must not become fare-model classification.
- `PassengerUIKit/Mobility/PassengerMobilityScreens.swift` uses map/compact route rows, mode menu, conditional schedule/airport inputs and bottom action; existing search/saved/recent/map/manual selection are reusable. Demo booking has separate factory/presentation/repository. English/Khmer resources and shared semantic tokens exist. Accessibility, dark mode, safe-area and keyboard implementation are not equivalent to full manual verification.

## Compatibility decisions for the next phase (not implemented)

Preserve BookingType and introduce a separate explicit FareModel only after this baseline. Desired mappings from the prior requirement are ON_DEMAND+QUOTED/METERED, SCHEDULED+QUOTED, AIRPORT+QUOTED/METERED; recurring stays unchanged. METERED must be explicit, forbid destination/route quote and leave fare pending; immediate must omit scheduled time/flight; airport requires verified flight fields and future time. These are target rules, not current confirmed API support.

Before enabling modes: reconcile local enum/schema/snapshots/mobile mappings; define legacy omitted-fare default and backfill for quoted rows; retain recurrence and legacy airport semantics deliberately; establish stable Problem Details combination errors; bind quote ownership/expiry/timing; audit assignment/planning, lifecycle/cash/outbox nullability, authorization and exact-body retry compatibility. Applied Flyway history is unknown: do not assume V15/V16 can be edited safely in any deployed environment. OpenAPI enum presence does not replace service validation or feature gates.

## Tests inspected and verification limits

Reviewed BookingFlowIT (happy path, transitions, concurrent assignment and idempotent booking), BookingRulesIT (quote ownership/expiry/payment, airport, corporate policy, recurring, authorization, cash), RecurrenceTest, AssignmentIT, QuoteIT, IdempotencyIT and OpenApiSnapshotIT. Local BookingModesIT covers destination-free payload/replay, airport inputs, quote timing and measurement failures/completion, but targets the conflicting enum design. PassengerMobilityTests covers API and presentation contracts; driver operational tests cover trip/location delivery. No focused FareModel matrix coverage exists because that contract is absent.

This phase performed source/HEAD comparisons, request/schema inspection and documentation validation only. No tests were added; no build, simulator inspection or backend mutation was run. Older implementation-guide passes are historical and do not certify the current baseline. The latest retained `/tmp/booking-backend-final.log` instead reports BUILD FAILURE: 41 tests errored during application startup because Testcontainers could not find a valid Docker environment. This is an environment failure, not 41 demonstrated behavior regressions or a passing suite. Deployed support, physical GPS, iOS 15 runtime and comprehensive Khmer/VoiceOver/device matrices remain unverified.
