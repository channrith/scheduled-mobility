---
name: staging-check
description: Read-only health and state check of the staging droplet (deployed commit, services, Flyway version, live Caddy/RabbitMQ config vs the files on disk, memory, swap, disk, pre-deploy dumps) plus the public endpoints, and how to interpret the result. Use this whenever the user asks whether staging is healthy or up, what version is deployed, why staging is slow or failing, before or after a deploy, before a migration, or when deciding whether the 2 GB droplet needs resizing, even if they only say "check the server".
---

# Checking staging

Everything here only reads. The user runs the server part and pastes the output; you run the public part
yourself and interpret both. The runbook is `docs/staging.md`.

## 1. From your side (public, no login)

```bash
for u in https://api.146-190-4-99.sslip.io/actuator/health https://loc.146-190-4-99.sslip.io/actuator/health; do echo "$u -> $(curl -s --max-time 10 "$u")"; done
echo "console /login: $(curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://console.146-190-4-99.sslip.io/login)"
# The API host is the gateway: its health is the gateway's own. These prove it reaches each backend.
curl -s -D - -o /dev/null --max-time 10 -H 'X-Request-Id: staging-check-0001' https://api.146-190-4-99.sslip.io/api/v1/me | grep -iE '^HTTP|^x-request-id'
echo "pings via gateway: $(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST -H 'Content-Type: application/json' -d '{}' https://api.146-190-4-99.sslip.io/api/v1/locations)"
curl -s "https://api.github.com/repos/channrith/scheduled-mobility/actions/runs?per_page=4" | python3 -c "import sys,json;[print(r['name'],r['head_sha'][:12],r['status'],r['conclusion']) for r in json.load(sys.stdin)['workflow_runs']]"
```

Expect `"status":"UP"` twice, `200`, then `HTTP/2 401` with `x-request-id: staging-check-0001` (core-app answered
through the gateway) and `pings via gateway: 401` (location-service answered). `UP` on the API host alone only
proves the **gateway** is up, not core-app. If hosts changed, take them from `docs/staging.md`.

| API host result | Meaning |
|---|---|
| `/actuator/health` UP, `/api/v1/me` 401 | gateway and core-app fine |
| `/actuator/health` UP, `/api/v1/me` 503 (`gateway.upstream-unavailable`) | gateway up, **core-app down**: ask for `docker compose logs --tail 100 core-app` |
| `/api/v1/me` 504 (`gateway.upstream-timeout`) | core-app up but answering slower than 30 s: memory or database; check the memory rows |
| pings 503 | location-service down (the direct `loc.` host is then down too) |
| everything 502 or no answer | Caddy cannot reach the gateway: `docker compose logs --tail 50 api-gateway caddy` |

A result of `000` (or `Could not resolve host`) in about a millisecond is the local resolver, not staging: it
has happened with sslip.io names even while `dig` resolved them. Retry with the IP pinned before reporting an
outage, e.g. `curl --resolve console.146-190-4-99.sslip.io:443:146.190.4.99 https://console.146-190-4-99.sslip.io/login`.

## 2. On the server (the user pastes this as `deploy` or root)

```bash
cd /opt/mobility
echo "=== version";   grep -E '^(IMAGE_TAG|STORAGE_TYPE|API_DOCS_ENABLED)=' .env
echo "=== services";  docker compose ps -a --format "table {{.Service}}\t{{.Status}}\t{{.RunningFor}}"
echo "=== flyway";    docker compose exec -T postgres psql -U postgres -d mobility -tAc "select version, description, installed_on from public.flyway_schema_history where success order by installed_rank desc limit 3"
echo "=== live config vs files"
docker compose exec -T caddy cat /etc/caddy/Caddyfile | cmp -s - Caddyfile && echo "Caddyfile: in sync" || echo "Caddyfile: STALE"
docker compose exec -T rabbitmq cat /etc/rabbitmq/conf.d/20-staging.conf | cmp -s - rabbitmq/20-staging.conf && echo "rabbitmq conf: in sync" || echo "rabbitmq conf: STALE"
echo "=== memory";    free -m; docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}"
echo "=== restarts";  for c in $(docker compose ps -q); do docker inspect -f '{{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' "$c"; done
echo "=== disk";      df -h /; docker system df
echo "=== dumps";     ls -lt backups | head -8
```

It prints no secrets: only the three named `.env` lines.

## 3. Interpreting

| Output | Healthy | Act when |
|---|---|---|
| `IMAGE_TAG` | matches the last successful Deploy staging run | differs: someone deployed by hand, or the last deploy failed |
| services | every app `(healthy)` (core-app, location-service, api-gateway, web-console), caddy `Up` | `Restarting`, `(unhealthy)`, `Exited (1)`: ask for `docker compose logs --tail 100 <service>` |
| `Exited (137)` / `oom=true` / restarts > 0 | none | out of memory: see memory rows |
| flyway | newest version = highest `V<n>__` in `core-app/src/main/resources/db/migration/` at that commit | lower: core-app failed before migrating; a `success=false` row means a failed migration |
| Caddyfile / rabbitmq conf | `in sync` | `STALE`: the container reads an old copy (single-file bind mount). Fix: `docker compose up -d --force-recreate --no-deps caddy` (or rabbitmq). `deploy.sh` normally does this |
| memory | total container use ~1.1 GB of 2 GB (api-gateway and location-service each ~190 MB of 256 MB), swap used a few hundred MB | swap used keeps growing across checks, a container near its limit, or any OOM: resize to 4 GB (values in `.env.staging.example`) |
| disk | under ~70% | `docker image prune -f` is safe; never `docker volume prune` |
| dumps | a `pre-deploy-*` per recent deploy (last 5) and any `manual-*` | none: no deploy since P0 or dumps failing; staging has no off-site backup, so suggest copying one to the user's Mac |

When something is wrong, say which row, what it means, and the single next command; don't restart anything
without the user agreeing, since staging is shared.
