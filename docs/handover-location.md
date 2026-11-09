# Handover — location service (Prompt 7)

Prompt 7 is split into 7a–7e (`docs/CLAUDE_CODE_PROMPTS.md`). Gateway, platform-security and rate-limit history:
`docs/handover-gateway.md`.

## Status
- **7a — driver ingestion: plan approved 2026-11-09 as proposed** (D1 core-app writes `status`/`tripId`; D2
  100 m, 160 km/h, 300 pings, 2 h; D3 bind core-app to 127.0.0.1 in `dev.sh` too).
  - **Step 1 done 2026-11-09, not committed:** `ping/PingIngestion` + `resources/ping/store_ping.lua` (filter,
    stale rule, write and `lastSeen` in one atomic script), `LocationProperties.Filter` (`location.filter.*`),
    `speedMps`/`headingDegrees` on the request, dropped pings answer 202 `{stored:false, reason}` with `stale`,
    `accuracy` or `speed`. The speed check only compares with a position under 2 min old, so a jump that slipped
    through blocks a driver for at most 2 min. `driver:state:<userId>` has `lastSeen` (any authenticated ping,
    TTL = `position-ttl`). Tests: `LocationPingIT` 7 (was 3). `check.sh --fast` passed.
  - Next: step 2 (batch endpoint + `ping-batches` policy).
- 7b–7e: not started.

## 7a inventory (2026-11-09)

| Piece | State |
|---|---|
| `POST /api/v1/locations` (DRIVER) | Exists. Body `latitude`, `longitude` (Cambodia box), `accuracyMeters`, `recordedAt` (≤ 10 min old, ≤ 1 min ahead); a late ping never overwrites a newer one. Logic sits in `LocationController`. |
| Redis contract | Exists: hash `driver:location:<userId>` (`lat`, `lng`, `recordedAt` ms, `accuracy`, TTL 24 h) + GEO `driver:positions`. Read by core-app `dispatch/risk/DriverPositions`. Read-then-write is not atomic. |
| Tokens | Done: platform-security, core-app JWKS, no keys held. |
| Gateway | Done: route `/api/v1/locations`, `/api/v1/locations/**`, `/ws/**`; policies `pings` (`POST /api/v1/locations` only, 10 burst, 1/s per user) and `ws-connect`. |
| Speed, heading | Missing. |
| Noise filter | Missing (only the bounding box and timestamp window). |
| Batch endpoint | Missing. A `/api/v1/locations/batch` path would fall through to the `default` policy today. |
| Driver state (`lastSeen`, `status`, `tripId`) | Missing. location-service has no trip data; core-app publishes `TripAssigned`, `TripStatusChanged`, `TripCompleted`, `TripCancelled`. |
| Not reachable directly | Missing locally: `dev.sh` runs location-service on 0.0.0.0:8081. On staging it has no published port, but Caddy's `LOCATION_HOST` block still reaches it (removal waits for the driver app). |
| `dev.sh` | Starts it; smoke checks a passenger ping gets location-service's 403 through the gateway. |
| `check.sh` | Already covered by `./mvnw verify` (unit + Testcontainers ITs). |

## 7a plan (proposed)

1. **Ping model and noise filter (location-service).** Move logic out of the controller into `ping/PingIngestion`.
   Add optional `speedMps` and `headingDegrees` (0–360) and keep the existing field names (the smoke test and
   core-app use them). Drop, with a reason, pings with accuracy over 100 m or an implied speed over 160 km/h since
   the last accepted ping (both configurable). A dropped ping answers 202 `{stored:false, reason}`, not 4xx, so the
   app doesn't retry it. Hash gains `speed`, `heading`, `lastSeen`; the read-compare-write becomes one Lua script
   (atomic, so a batch and a live ping can't race).
2. **Batch endpoint.** `POST /api/v1/locations/batch`, up to 300 pings (20 min at 4 s), up to 2 h old, sorted by
   time and filtered one by one; only the newest one can update the latest position. Answers counts
   `{accepted, dropped}`. Gateway: new `ping-batches` policy, 3 burst, 1 per 10 s per user, so the batch is one
   token and never falls to `default`. `pings` stays as it is (4 s and 15 s intervals use well under 1/s).
3. **Driver status and trip (D1).** core-app's dispatch module listens to the trip events and writes `status`
   (`IDLE` / `ON_TRIP`) and `tripId` into `driver:state:<userId>`; location-service only writes `lastSeen`.
4. **Not reachable directly (local).** `dev.sh` starts location-service with `SERVER_ADDRESS=127.0.0.1`; smoke checks
   that the Mac's LAN address on 8081 is refused, the gateway still reaches it, and a passenger batch gets 403.
5. **Docs.** README, handover-gateway (policy table), this file, Progress in `CLAUDE_CODE_PROMPTS.md`.

Each step: tests, `scripts/check.sh`, commit message.

### Decisions waiting
- **D1** Who writes `status` and `tripId`: core-app from trip events (recommended, trusted) or the driver app in each ping.
- **D2** Thresholds: accuracy 100 m, 160 km/h, batch 300 pings and 2 h.
- **D3** Also bind core-app to 127.0.0.1 in `dev.sh` (same reasoning), or location-service only.
