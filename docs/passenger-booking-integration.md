# Passenger booking: final integration and verification

Updated 2026-11-01 (Asia/Phnom_Penh). This is the current Phase 3/4 passenger guide. The Phase 1 audit is historical; [Phase 2](phase2-booking-contract.md) defines the reconciled backend contract. Both repositories still have uncommitted work. Nothing was deployed or published.

## Product and actual wire mapping

Home exposes one Now action, Later and Airport pickup; Saved places and My rides remain independent. Now has a small destination choice inside the request screen. Airport passenger entry always requires a destination and future time; airport metered and recurring are not exposed. Older airport metered rides/pending requests can still be read or reconciled without offering a new entry.

| Passenger selection | Quote POST `/api/v1/quotes` | Booking POST `/api/v1/rides` | Server ride response |
| --- | --- | --- | --- |
| Now + destination | pickup, **dropoff**, vehicleClass, fareModel=QUOTED; **pickupAt omitted** | type=ON_DEMAND, fareModel=QUOTED, quoteId, CASH | ON_DEMAND + QUOTED; destination/fare present |
| Now + no destination | **No quote call** | type=ON_DEMAND, fareModel=METERED, pickup, vehicleClass, CASH; **no dropoff, quoteId or pickupAt** | ON_DEMAND + METERED; destination/fare absent, fare pending |
| Later + destination | pickup, **dropoff**, vehicleClass, **future pickupAt**, fareModel=QUOTED | type=SCHEDULED, fareModel=QUOTED, quoteId, CASH | SCHEDULED + QUOTED; destination and future trip pickupAt present |
| Airport passenger | airport pickup, **dropoff**, vehicleClass, **future pickupAt**, fareModel=QUOTED | type=AIRPORT, fareModel=QUOTED, quoteId, flightNo, meetingPoint, CASH | AIRPORT + QUOTED; destination, flight/meeting and future trip pickupAt present |

**There is no scheduledAt field in this backend.** The existing wire names are `dropoff` and `pickupAt`. For quoted bookings, destination/time/vehicle are bound to the immutable quote and must not be repeated as direct booking overrides: the backend rejects those overrides. Thus scheduled time is present in the quote request and returned trip, not inline in the final booking POST. All new passenger booking bodies also send contractVersion=2. Optional passenger notes are separate from meetingPoint. No fabricated coordinates, quote or fare are sent.

BookingType retains exactly SCHEDULED, AIRPORT, RECURRING, ON_DEMAND. PassengerBookingType mirrors those serialized values; PassengerFareModel mirrors QUOTED/METERED. PassengerBookingMode remains the typed presentation intent. No independent scheduled/airport/metered booleans were added to draft state. Read DTOs carry explicit FareModel; absent legacy FareModel defaults to QUOTED, never inferred from missing destination.

## Availability and journey

The authoritative discovery field is `/booking/options.combinations`, containing type/fareModel pairs. An explicit empty array disables entries even if legacy modes lists enum names. Unknown pairs are ignored. With older discovery lacking combinations, or an absent route (404), only established quoted scheduled/airport support is considered; no immediate/metered capability is inferred. Other errors remain failures.

Home uses a compact map panel, no blocking warning popover and no separate Metered Home action. If neither Now variant is enabled, Now has one short inline unavailable explanation; Saved places/My rides stay usable. Now opens the available immediate variant if only one is enabled. The request form shows compact pickup/destination rows with a connector, hides destination for meter, shows Leaving now for immediate service and dates only for Later/Airport. Search, saved/recent places, manual address/map recovery and authorized location prefill use the existing architecture. Unresolved manual text cannot be sent as invented coordinates.

Quoted review shows server fare/equivalent/breakdown/expiry. An expired quote cannot confirm; Refresh fare remains available. Meter review makes no quote call and shows no amount. The final meter fare remains a backend completion concern. Cash copy does not imply bank settlement or payment finalization at creation. Airport has required flight and meeting fields, with no flight tracking or automatic terminal integration claim.

Draft edits, injected clock, account-scoped draft reset, cancellation, retry and duplicate-confirmation protection remain. Account/origin-scoped Keychain storage preserves exact body/key for uncertain booking results. Phase 4 fixed an upgrade mismatch: legacy experimental meter journals are compared by semantic input but replay their **original bytes/key**, allowing a cached result or definite backend rejection to resolve the journal. They are never silently rewritten into a new booking operation.

## Architecture and rollout

Passenger features remain in PassengerModules; no modules were moved or dependencies added. Foundation domain, shared PassengerPresentation, constructor injection, UIKit coordinator/UINavigationController and injectable factory interfaces are preserved. Existing shared DesignSystem comes from SharedModules, with no driver-feature imports. The passenger tree has no SwiftUI renderer; none was invented. iOS 15 deployment remains unchanged.

Backend business rules were not changed in Phase 3/4. The only Phase 4 backend code change is to the opt-in simulator test fixture's hold loop, allowing deletion of its private temporary file to stop it gracefully. Feature flags remain default off: BOOKING_ON_DEMAND_ENABLED and BOOKING_METERED_ENABLED. Local verification enabled them only in the existing test profile. Scheduled/quoted airport and backend recurring are established flows; immediate/metered were exercised on disposable infrastructure, not declared production-ready. Manual dispatch is required for unknown destinations. Physical GPS trust/calibration/background continuity, automatic immediate matching SLA and payment settlement remain release gaps.

## Checks actually completed

- ScheduledMobilityPassenger Debug builds successfully for Mobility Socket QA, iOS 26.2, with minimum deployment iOS 15.0.
- Final passenger harness: **64 tests passed**, including an opt-in end-to-end case that exercised **all four passenger modes through the actual PassengerAPIMobilityRepository and PassengerBookingViewModel against real backend controllers/PostgreSQL/Redis**. It inspected actual quote/booking bodies, decoded server FareModel, read each ride, prevented a repeated confirmation, and cancelled every synthetic booking. No metered route quote, destination or fare was fabricated.
- Existing offline/session/booking tests cover exact-body retry, expiry, malformed responses, manual location without coordinates, account scoping and capability handling. Focused additions cover explicit type/fare mapping, unsupported airport meter entry and legacy uncertain-request reconciliation.
- Simulator manually inspected: English/light Home and both Now variants; Airport's destination/date/flight/meeting form without an airport meter selector; text editing and visible bottom action; Khmer/dark Home at standard text; largest accessibility text rendering and panel expansion. Accessibility labels were inspected via the tree. Full spoken VoiceOver, software-keyboard/small-screen/landscape coverage and access to every lower action at the largest Khmer size were **not fully verified**. No iOS 15 runtime or physical device was tested.
- Passenger mobility and identity contract drift checks pass against final Phase 2 OpenAPI. Passenger and lifecycle pins record the current OpenAPI digest and uncommitted-source marker. English/Khmer resources lint; git diff whitespace check passes.
- Phase 2 historical checks: nine focused unit/module checks, 34 executed targeted integration tests, then eight executed checks including populated V16→V17 migration. Those are recorded separately in the Phase 2 guide, not a newly run full backend suite in Phase 4.

The opt-in backend simulator fixture finished with BUILD SUCCESS after graceful shutdown. Its private token file was removed, Testcontainers cleaned up its infrastructure, and the loopback bridge was stopped. Simulator appearance/text settings were restored; the QA app was closed.

The simulator uses BookingModesIT#simulatorPreview and a loopback-only bridge. Only OTP UI is scripted (synthetic phone/code); mobility calls hit the real disposable backend. Coordinates/places are labelled synthetic fixtures, not production airport data. No real account, SMS, reservation, payment, deployment or feature-flag change in production occurred.

## Reproduce

1. Backend: `./mvnw -pl core-app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BookingModesIT#simulatorPreview -Dbooking.preview.seconds=900`.
2. iOS: `python3 scripts/booking-modes-preview-proxy.py` (binds 127.0.0.1:18934, no token/body logging).
3. `PASSENGER_BOOKING_PREVIEW_URL=http://127.0.0.1:18934 scripts/test-passenger-identity.sh`. Without opt-in/fixture, the end-to-end case skips and the remaining harness is offline.
4. Launch the passenger simulator with `-passenger-api custom -passenger-api-url http://127.0.0.1:18934`; fixture phone `+855971111111`, code `1234`. Do not use a real number/account.
5. Remove only the fixture-owned `/tmp/mobility-booking-preview.json` to end the hold early, or let it expire; the test cleans up containers. Stop the loopback bridge afterward. Restore simulator appearance/text settings after inspection.

Contract check: `python3 scripts/check-passenger-mobility-contract.py --backend-dir /path/to/backend`. No UI or dependency changes are required to repeat it.
