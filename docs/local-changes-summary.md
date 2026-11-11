# Local changes — 2 November 2026

These changes are local. They have not been pushed or deployed.

## Booking contract

- Keep the four booking types: SCHEDULED, AIRPORT, RECURRING and ON_DEMAND.
- Represent QUOTED and METERED fares separately through FareModel.
- Enforce valid booking/fare combinations, quote ownership and timing, scheduling limits, airport pickup and meeting-point requirements.
- Expose enabled combinations and scheduling policy through GET /api/v1/booking/options.
- Keep on-demand and metered booking behind independent, default-off configuration flags.

## Metered trips and pricing

- Store immutable pricing terms when a metered booking is created.
- Accept authenticated driver location samples and validate their timing, accuracy and plausible movement.
- Finalize metered fares from accepted distance and trip duration when the trip completes.
- Return nullable destination/fare data and the explicit fare model in booking and driver views.
- Exclude destination-free trips from automatic route suggestions; manual dispatch remains available.

## Database and API documentation

- Add migrations V15–V17 for booking modes, quote timing and explicit fare-model constraints/backfill.
- Update localized errors, OpenAPI and generated console API types.
- Add booking combination, metering and populated-database migration coverage.
- Document the contract, integration requirements and rollout limitations. See phase2-booking-contract.md for the current contract; older handovers describe superseded experiments.

## Main branch integration

- Merge origin/main through 78f6b09 in merge commit abbed50.
- Resolve location-service JWKS configuration and test conflicts while retaining tracking and readiness behavior.
- Remove obsolete PEM configuration notes and rename the internal meter result to MeteredFare.

## Readability cleanup

- Format modified and new Java production/test files consistently with four-space indentation.
- Expand compact declarations, method calls and expressions onto readable lines; normalize spacing and wrapping.
- Preserve behavior and generated API files. No formatter dependency was added to the project.
