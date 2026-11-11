# Local quote setup

From the repository root, with local Compose PostgreSQL running and the updated
backend started once to apply Flyway V18:

```bash
./scripts/seed-pricing.sh
```

The seed adds missing PHNOM_PENH city/airport zones and public pricing rules for
MOTO, TUKTUK_REMORK, TUKTUK_RICKSHAW, CAR, SUV and VAN. It runs transactionally and
can be repeated. Existing zone types and price lines (including retired lines)
are preserved. No backend restart is needed for database changes.

These are development fixtures: approximate rectangular Phnom Penh, PNH and KTI
zones and illustrative USD fares, not approved boundaries or commercial tariffs.
USD amounts are cents. Missing pricing settings are persisted as fuel index
1.0000 and sample USD/KHR rate 4100. Existing settings are preserved. These values
are development fixtures, not a live exchange-rate feed.
With the default local STRAIGHT_LINE routing, distances and
travel times are estimates.

Example authenticated request:

```http
POST http://localhost:8090/api/v1/quotes
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "pickup": {"latitude": 11.5697, "longitude": 104.9210},
  "dropoff": {"latitude": 11.5480, "longitude": 104.9343},
  "vehicleClass": "CAR"
}
```

For a scheduled quote, include a future `pickupAt` in ISO 8601 format. For a
metered booking, skip quote creation; the quotes API accepts quoted fares only.
The seed targets only this repository's local Compose PostgreSQL service.

The seed also adds an unverified, clearly named **Local demo - KTI zone test point
(not surveyed)** at `11.3633,104.9211`. It leaves the original Techo catalog
coordinate unchanged. See [the passenger API handover](handover-passenger-locations.md)
for the coordinate mismatch and provider/migration setup.
