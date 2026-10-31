---
name: deploy-rehearsal
description: Rehearse changes to the staging deploy setup (deploy/staging/deploy.sh, compose.yml, Caddyfile, generate-secrets.sh, a new service or image) locally on macOS with Linux bind-mount semantics, a local registry and deliberately broken images, before they are committed and deployed. Use this whenever deploy/staging/ or the deploy workflow changes, a new deployable is added to staging, rollback or migration handling is touched, or the user asks to test or verify the deploy process, even if they just say "make sure the deploy still works".
---

# Rehearsing a deploy-setup change

Run the real `deploy/staging/` bundle against a local Docker stack and drive `deploy.sh` through success and
failure scenarios. A plain `docker compose up` on macOS is **not** a valid test, for two reasons found the hard
way:

- Docker Desktop shares macOS files **by path**. On the droplet (Linux), a single-file bind mount
  (`./Caddyfile`) keeps pointing at the old inode after rsync replaces the file, so changes silently never
  reach Caddy. On macOS that bug cannot happen, so tests pass falsely. Hence `scripts/linux-run.sh`.
- `docker compose up` can block for 20+ minutes when a service never becomes healthy, depending on the
  Compose version. Always check how long a failed deploy really takes.

Work in the session's scratchpad, never in the repository. The whole rehearsal takes 20 to 40 minutes,
mostly image builds and the broken-image timeouts.

## 1. Images, built the way CI builds them

Take the module list from the `images` job matrix in `.github/workflows/ci.yml`. Java modules build from the
repository root with `--build-arg MODULE=<module>`; `web-console` builds from `web-console/`.

```bash
docker build -t localhost:5055/core-app:good --build-arg MODULE=core-app .
docker build -t localhost:5055/web-console:good web-console      # and so on for every module
```

Variants (tag every module under each tag; only the broken one differs):

| Tag | How | Tests |
|---|---|---|
| `good` | as built | normal deploy |
| `broken` | `FROM localhost:5055/core-app:good` + `ENTRYPOINT ["sleep", "infinity"]` (for web-console: `CMD [...]`) | automatic rollback |
| `migbroken` | `FROM ...core-app:good`, `COPY V99__rehearsal.sql /extra-migrations/`, `ENV SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/extra-migrations`; the SQL drops a column an entity needs (e.g. `ALTER TABLE identity.users DROP COLUMN telegram_id;`) | Flyway applies it, Hibernate validation fails, `deploy.sh` must NOT roll back |

Local registry, then push every tag:

```bash
docker run -d --name deploy-registry -p 5055:5000 registry:2
docker push -q localhost:5055/<module>:<tag>
```

## 2. The bundle

1. Copy `deploy/staging/` to a scratch folder. In it: `PUBLIC_IP=127.0.0.1 ./generate-secrets.sh`, then set
   `ACME_EMAIL`, `BOOTSTRAP_ADMIN_PHONE`, and `IMAGE_REPOSITORY=localhost:5055` in `.env`.
   To mirror the droplet, keep any value the droplet sets explicitly (its `.env` came from an older example
   file) and leave out settings the droplet doesn't have yet: that tests the "compose configuration is
   incomplete" path.
2. Replace the Caddyfile's global block (`email ...`) with `{ local_certs }` so no real certificates are
   requested. Keep everything else identical: `deploy.sh` compares this exact file with the container's copy.
3. `compose.override.yml` resetting the data stores' host ports (the local dev stack uses them):
   `ports: !reset []` for postgres, redis and rabbitmq.
4. `mkdir curlhome && echo insecure > curlhome/.curlrc`, so `deploy.sh`'s HTTPS checks accept Caddy's local CA.
5. Copy the folder into a volume and build the runner image:

```bash
docker volume create rehearsal-bundle
docker run --rm -v rehearsal-bundle:/var/lib/docker/volumes/rehearsal-bundle/_data -v "$PWD:/src:ro" \
  alpine cp -a /src/. /var/lib/docker/volumes/rehearsal-bundle/_data/
printf 'FROM docker:27-cli\nRUN apk add --no-cache bash curl coreutils\n' | docker build -t rehearsal-cli -
```

From then on, change bundle files only through the runner (`linux-run.sh sed -i ...`); `sed -i` writes a new
inode exactly like rsync.

## 3. Scenarios

Run them with `.claude/skills/deploy-rehearsal/scripts/linux-run.sh <cmd>`. Use `DEPLOY_TIMEOUT_SECONDS=90`
for the broken ones.

| # | Scenario | Expect |
|---|---|---|
| 1 | Required `.env` value missing | `compose configuration is incomplete`, exit 1, no dump, nothing started |
| 2 | First deploy `good` | `no pre-deploy dump`, `healthy`, both HTTPS URLs up |
| 3 | `sed -i` a visible change into the Caddyfile, check the container still shows the old value, deploy `good` | `Caddyfile changed; recreating caddy`; the container then shows the new value. A redeploy without changes recreates nothing |
| 4 | Deploy `broken` | rolls back to `good` within about 2 x the budget; the job would fail |
| 5 | Deploy `migbroken` | `Flyway applied 1 new migration(s): NOT rolling back`, naming the dump |
| 6 | Restore that dump with the commands in `docs/staging.md` ("Failed deploy after a migration"), deploy `good` | `pg_restore` exit 0, healthy, migration count back |
| 7 | Feature-specific: a new service's headers and health, an upload through the new limit, memory peaks with `docker stats` sampled once a second | as designed; raise a memory limit rather than ship one above ~80% |

Also confirm retention (only the last 5 `pre-deploy-*` dumps remain) and `shellcheck` on changed scripts
(`docker run --rm -v "$PWD/deploy/staging:/mnt" koalaman/shellcheck:stable /mnt/deploy.sh`).

## 4. Clean up

```bash
.claude/skills/deploy-rehearsal/scripts/linux-run.sh docker compose down -v
docker volume rm rehearsal-bundle
docker stop deploy-registry && docker container rm deploy-registry
```

Remove `localhost:5055/*` tags with plain `docker rmi <name:tag>`, never `docker rmi -f <image id>`: tags that
share an ID with other local images (e.g. a `mobility/core-app:dev`) get deleted too.

## Known traps

- The Bash safety check refuses a shell `-c` script that contains `rm` or sits next to `--rm`. Keep removals
  as separate, explicit commands with literal paths, and use the runner script instead of inline `sh -c`.
- Docker Hub pulls sometimes fail with `no such host` (local DNS): retry after a few seconds.
- `minio/minio` images are no longer on Docker Hub. For S3 tests use `adobe/s3mock` (the app's SDK works),
  but the AWS CLI's `Expect: 100-continue` breaks on S3Mock: test `backup.sh` against
  `chrislusf/seaweedfs` (`server -s3`).
- A `000` from curl in about a millisecond is the Mac's resolver, not the stack: retry with `--resolve`.
