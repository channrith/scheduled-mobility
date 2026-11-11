# Phase 2: backend booking and fare-model reconciliation

This phase changes backend code only. Passenger iOS UI and wire adapters are unchanged and still need a later compatibility update. The [Phase 1 audit](booking-architecture-audit.md) remains a dated record of the pre-reconciliation working tree.

## Contract

BookingType is restored byte-for-byte from the committed baseline: SCHEDULED, AIRPORT, RECURRING, ON_DEMAND. There are no new booking-type wire values. The canonical FareModel lives in the public pricing module API (`com.mobility.core.pricing.FareModel`), allowing booking to consume it without creating a pricing→booking dependency cycle.

| Type | FareModel | Route/time requirements |
| --- | --- | --- |
| ON_DEMAND | QUOTED | Owned, unexpired NOW quote supplies pickup/destination/vehicle; direct pickupAt and flight absent |
| ON_DEMAND | METERED | Explicit fareModel, pickup and vehicle; destination, quoteId, pickupAt and flight absent |
| SCHEDULED | QUOTED | Scheduled quote supplies route and future pickup time |
| AIRPORT | QUOTED | Scheduled airport quote and flight; explicit new contract requires airport pickup and meetingPoint |
| AIRPORT | METERED | Airport pickup, vehicle, future pickupAt, flight and meetingPoint; quote and destination absent |
| RECURRING | QUOTED | Existing weekday/until expansion and per-occurrence policy/fare contract |

Omitted fareModel defaults to QUOTED for old callers. Missing destination never selects METERED. SCHEDULED/RECURRING+METERED is rejected. Invalid type/fare/field combinations return RFC 7807 with stable `booking.invalid-combination` and field context. Quoted route fields come from quoteId; direct destination overrides are rejected. POST quotes rejects explicit METERED with `pricing.quote-not-eligible`. Quote response data and pricing continue to come from the server.

Existing wire time is `pickupAt`, not a new scheduledAt alias. Future scheduling uses the injected backend policy/clock; immediate quoted rides require immutable quote timing NOW and remain valid until the actual quote expiry. RECURRING retains the original 90-day inclusive expansion and local time semantics. Legacy airport transfer requests omitting fareModel/contractVersion retain the existing either-end airport contract. Explicit fareModel or contractVersion=2 selects airport pickup/meeting-point validation. No live flight integration is claimed.

Ride detail and trip-with-ride responses include explicit fareModel, alongside nullable destination/fare and fareStatus. Meeting point is stored separately from passenger notes. Metered creation stores pricing terms but no invented quote, destination or final amount. Existing local measurement completion remains the only meter-finalization path; cash reporting requires completed/finalized fare. This is not payment settlement.

## Persistence compatibility

New additive Flyway V17 reconciles the earlier experimental V15 schema without modifying V12/V15/V16 checksums. It adds/backfills non-null fare_model, explicitly maps experimental METERED→ON_DEMAND+METERED and AIRPORT_METERED→AIRPORT+METERED, and defaults all other historical rows to QUOTED. It replaces the affected checks with four-type and explicit fare-model consistency checks. It does not infer historical meter status from null destination. Historical experimental airport rows without meetingPoint remain migratable; new requests enforce the meeting-point requirement in the service.

Applied migration history is unknown outside disposable tests. Review the migration chain and database history before deployment; no production migration or deployment occurs in this phase. Old passenger clients emitting experimental booking-type names will be rejected; do not deploy their metered UI against this contract unchanged. Existing legacy quoted payloads remain supported.

## Availability and operational boundaries

`BOOKING_ON_DEMAND_ENABLED=false` and `BOOKING_METERED_ENABLED=false` are independent default-off gates. ON_DEMAND+METERED requires both. AIRPORT+METERED requires the meter gate. Scheduled/airport quoted remain the established personal flows. Tests opt into both flags against disposable infrastructure.

GET `/api/v1/booking/options` returns authoritative `{type, fareModel}` combinations. The retained modes field lists enabled quoted BookingType values for older capability readers; it never emits experimental enum names. Discovery and creation enforce the same gates.

The original ON_DEMAND rejection has become a rollout policy guard rather than unconditional availability. Existing assignment authorization/version checks, state machine, cancellation, outbox and idempotency remain in use. Unknown destinations remain excluded from automatic route planning; manual dispatch is required. No instant matching SLA is established. GPS meter calibration, device telemetry trust, background continuity and recovery remain release blockers, so metered support is not production-ready. Payment module checkout/settlement is still absent. No mobile UI, flight-status or new bank payment integration is added.

## Verification

Focused BookingCombinationTest covers the type/fare pair matrix, forbidden immediate time/flight/quote combinations and legacy QUOTED/default-closed policy. Existing BookingModesIT is adapted to explicit fareModel and checks wire/read mapping, invalid pairs, omitted-fare behavior, no route quote for meter, replay and measured completion. Existing recurring, booking, quote, assignment and OpenAPI checks are reused.

- Java main/test compilation succeeded (Java 21).
- Nine focused unit/module-boundary checks passed: BookingCombinationTest, RecurrenceTest and ModularityTests.
- Targeted booking/quote/assignment/OpenAPI integration run: 34 executed tests passed, one optional simulator hold fixture skipped.
- After the final migration change, eight executed integration checks passed (BookingModesIT, BookingFareMigrationIT and OpenApiSnapshotIT), with the same optional fixture skipped. The populated-database test migrated V16→V17, preserved a quoted amount, translated both experimental types including an airport row without meetingPoint, and verified database rejection of invalid fare/type updates.
- OpenAPI was generated by the running backend snapshot test. Generated console API types and TypeScript checks were refreshed separately; no console product flow was added.
- Documentation links, four-value OpenAPI booking enums and git diff whitespace were checked.
- No iOS changes/build/simulator test, full backend suite, deployed migration, physical GPS calibration or production operation was performed. Docker initially was unavailable; starting the installed runtime enabled disposable Testcontainers verification. Disk pressure was relieved only by removing three task-generated temporary iOS build caches; source files were unaffected.
