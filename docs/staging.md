# Staging runbook

Staging runs everything on one DigitalOcean droplet with Docker Compose (`deploy/staging/`).

| | |
|---|---|
| Droplet | `mobility-staging-01`, SGP1, Ubuntu 24.04, 1 vCPU / 2 GB, tags `mobility`, `staging` |
| Reserved IP | `146.190.4.99` |
| API | https://api.146-190-4-99.sslip.io (Swagger: `/swagger-ui.html`) |
| Location service | https://loc.146-190-4-99.sslip.io |
| Admin console | https://console.146-190-4-99.sslip.io (staff only; same test-data rule) |
| Firewall | `mobility-staging-fw` (tag `staging`): inbound 22, 80, 443 only |
| Images | `ghcr.io/channrith/scheduled-mobility/{core-app,location-service,web-console}:<commit SHA>`, built by CI on `main` after the backend and console checks pass |
| Server directory | `/opt/mobility`, owned by the `deploy` user. `.env` there holds every secret and exists nowhere else |

**Test data only.** Staging sets `OTP_FIXED_CODE=1234`: anyone who knows a phone number can log in as that
user, including the admin. Never enter real ID numbers, bank accounts or documents, in the API or in the admin
console. Real driver onboarding waits for a production environment.

**No backups yet.** Until Spaces exists (see [Moving to Spaces](#moving-to-spaces)), documents live in a Docker
volume and the database has no off-site copy. Destroying the droplet loses both.

## 1. One-time server setup

As `root` (`ssh root@146.190.4.99`):

```bash
apt-get update && apt-get -y upgrade

# 2 GB swap: the stack fits in memory, swap absorbs spikes (image pulls during a deploy).
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/99-swap.conf && sysctl --system >/dev/null

# Docker Engine + Compose plugin from Docker's apt repository.
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update && apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Deploy user: runs docker compose, used by GitHub Actions. Membership of the docker group is
# root-equivalent, so its SSH key is as sensitive as root's.
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
install -d -o deploy -g deploy -m 750 /opt/mobility
install -d -o deploy -g deploy -m 700 /home/deploy/.ssh

# Key login only (DigitalOcean's default when the droplet was created with an SSH key; this makes it explicit).
printf 'PasswordAuthentication no\nPermitRootLogin prohibit-password\n' > /etc/ssh/sshd_config.d/90-hardening.conf
systemctl reload ssh
```

Security updates install automatically (Ubuntu's `unattended-upgrades`). When `ssh` greets you with
"System restart required", reboot at a quiet moment: `reboot`. All containers restart on their own.

## 2. Deploy key and GitHub secrets

On your own machine, create a key pair used only by GitHub Actions:

```bash
ssh-keygen -t ed25519 -N "" -C github-actions-staging -f ./staging-deploy-key
ssh root@146.190.4.99 'cat >> /home/deploy/.ssh/authorized_keys && chown deploy:deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys' < staging-deploy-key.pub
ssh -i staging-deploy-key deploy@146.190.4.99 'docker ps'   # must work without a password

ssh-keyscan -t ed25519 146.190.4.99 2>/dev/null > staging-known-hosts
ssh-keygen -lf staging-known-hosts
```

Compare the fingerprint printed by the last command with the one on the droplet itself
(DigitalOcean console → Access → Launch Droplet Console): `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.
They must match.

In GitHub: **Settings → Environments → New environment** `staging`, then add these environment secrets:

| Secret | Value |
|---|---|
| `STAGING_HOST` | `146.190.4.99` |
| `STAGING_SSH_USER` | `deploy` |
| `STAGING_SSH_KEY` | contents of `staging-deploy-key` (the private key) |
| `STAGING_SSH_KNOWN_HOSTS` | contents of `staging-known-hosts` |

Then delete the local copies: `rm staging-deploy-key staging-deploy-key.pub staging-known-hosts`.
(Optional: under the environment, add yourself as a required reviewer so every deploy needs a click.)

## 3. First configuration

From the repository root on your machine, copy the bundle (later deploys sync it automatically):

```bash
rsync -rlptv --exclude '.env' --exclude 'backups/' deploy/staging/ root@146.190.4.99:/opt/mobility/
ssh root@146.190.4.99 'chown -R deploy:deploy /opt/mobility && chmod 750 /opt/mobility'
```

On the server, as `deploy` (`ssh root@146.190.4.99`, then `su - deploy`):

```bash
cd /opt/mobility
PUBLIC_IP=146.190.4.99 ./generate-secrets.sh   # PUBLIC_IP: the reserved IP, not the droplet's own
nano .env                                      # set ACME_EMAIL and BOOTSTRAP_ADMIN_PHONE
./generate-secrets.sh                          # should no longer list anything to fill in
```

`generate-secrets.sh` only fills empty values and never changes existing ones. Keep it that way: a new
`PII_ENCRYPTION_KEY` makes every stored ID number and document unreadable, and new database passwords lock
the apps out of the existing database.

## 4. Deploy

GitHub → **Actions → Deploy staging → Run workflow** on `main`. With `image_tag` empty it deploys the commit
the run starts from; CI must have passed on that commit (the job checks that both images exist).

The workflow syncs `deploy/staging/` to `/opt/mobility` (never touching `.env` or `backups/`) and runs
`./deploy.sh <sha>`:

1. Check that `.env` has every value `compose.yml` needs (stops with the missing name, e.g. after a new host
   was added). Then dump the database to `backups/pre-deploy-<UTC time>-<previous tag>.dump` (mode 600, last 5 kept). If the
   dump fails, nothing is changed.
2. Pull, restart, and wait for core-app, location-service and web-console to be healthy (one 5-minute budget
   per attempt).
3. Recreate Caddy or RabbitMQ when their config file (`Caddyfile`, `rabbitmq/20-staging.conf`) changed. A
   single bind-mounted file keeps pointing at the old copy after rsync replaces it, so without this a
   changed Caddyfile would never take effect.
4. Check `https://<API_HOST>` and `https://<CONSOLE_HOST>/login`.

If the apps do not become healthy, it switches back to the previous tag and the job fails, unless the new
version applied database migrations: see [Failed deploy after a migration](#failed-deploy-after-a-migration).
The dumps live on the droplet itself, so they protect against a bad deploy, not against losing the droplet.

The first start takes a few minutes: images download, Flyway creates the schema, and Caddy obtains the
certificates. Then check:

```bash
curl https://api.146-190-4-99.sslip.io/actuator/health          # {"status":"UP",...}
curl https://loc.146-190-4-99.sslip.io/actuator/health
curl -X POST https://api.146-190-4-99.sslip.io/api/v1/auth/otp/request \
  -H 'Content-Type: application/json' -d '{"phone":"<BOOTSTRAP_ADMIN_PHONE>"}'
curl -X POST https://api.146-190-4-99.sslip.io/api/v1/auth/otp/verify \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"phone":"<BOOTSTRAP_ADMIN_PHONE>","code":"1234"}'           # accessToken; /api/v1/me shows ADMIN
```

## Day-to-day operations

All commands on the server as `deploy`, in `/opt/mobility`.

| Task | How |
|---|---|
| Status | `docker compose ps` |
| Logs | `docker compose logs -f --tail 100 core-app` (JSON lines; also `caddy`, `postgres`, ...) |
| Deploy / roll back | Run the **Deploy staging** workflow with the commit SHA to run (an older SHA rolls back). By hand: `./deploy.sh <full sha>` |
| Which version is running | `grep IMAGE_TAG .env` |
| Restart one service | `docker compose restart core-app` |
| Change configuration | Edit `.env`, then `docker compose up -d` (recreates only what changed) |
| Database shell | `docker compose exec postgres psql -U postgres -d mobility` |
| RabbitMQ UI, GUI database tools | SSH tunnel, see [Connecting from your machine](#connecting-from-your-machine) |
| Memory | `docker stats --no-stream`, `free -h` |
| Disk | `df -h /`, `docker system df`; old images are pruned after each successful deploy |

### Connecting from your machine

Postgres, Redis and RabbitMQ listen on the droplet's loopback (`127.0.0.1`) only: unreachable from the
internet, reachable through SSH. Open one tunnel and leave it running (local ports differ from the local
development stack, so both can run at once):

```bash
ssh -N -L 15432:127.0.0.1:5432 -L 16379:127.0.0.1:6379 -L 15674:127.0.0.1:5672 -L 15673:127.0.0.1:15672 root@146.190.4.99
```

| Service | Connect to | User | Password (on the server: `grep '^NAME=' /opt/mobility/.env`) |
|---|---|---|---|
| PostgreSQL, database `mobility` | `localhost:15432` | `mobility_app` | `APP_DB_PASSWORD` |
| Redis | `localhost:16379` | (none) | `REDIS_PASSWORD` |
| RabbitMQ (AMQP) | `localhost:15674` | `mobility` | `RABBITMQ_PASSWORD` |
| RabbitMQ management UI | http://localhost:15673 | `mobility` | `RABBITMQ_PASSWORD` |

Use `mobility_app` for day-to-day work: it can do what the app can, but not drop the database or manage
roles. The `postgres` superuser (`POSTGRES_SUPERUSER_PASSWORD`) is for administration only.

GUI tools (DBeaver, TablePlus, DataGrip, RedisInsight) can open the tunnel themselves: SSH host
`146.190.4.99`, user `root`, your SSH key; then database host `127.0.0.1` and the server-side port
(`5432`, `6379`, `5672`).

Staging uses the fixed login code and holds test data, but these are still the live staging stores:
changing rows or flushing Redis affects everyone testing.

**Rollback and migrations:** rolling back the app does not roll back the database. An older version only
starts against a newer schema when the newer migrations were purely additive (new tables, nullable columns).
Dropping or renaming a column, or changing a constraint the old code relies on, makes the old version fail
Hibernate's schema validation. That is why `deploy.sh` does not roll back automatically once a deploy has
applied migrations. To keep automatic rollback, split such changes over two releases (expand, then contract):
first add the new column and write both, then drop the old one in a later release.

**Reset all staging data** (database, Redis, RabbitMQ, documents, certificates): `docker compose down -v`,
then deploy again. On the next start the admin is bootstrapped again. This cannot be undone.

**Memory on 2 GB:** the limits add up to about 2.1 GB on purpose; actual use is about 1 GB, and swap covers
peaks. The console's 256 MB is sized for uploads: two maximum-size two-sided uploads at once peaked at about
200 MB. If containers restart with `Exited (137)` or swap stays busy, resize.

**Resize to 4 GB:** power off, resize in DigitalOcean (CPU and RAM only, so it can be resized back), power
on, then set the 4 GB values listed in `.env.staging.example` in `.env` and run `docker compose up -d`.

## Moving to Spaces

When document storage and backups should leave the droplet:

1. In DigitalOcean, create two private buckets in SGP1 (file listing restricted, CDN off), e.g.
   `mobility-staging-documents` and `mobility-staging-backups`, and one limited-access key per bucket
   (read/write on that bucket only).
2. In `.env`, set `STORAGE_S3_BUCKET`, `STORAGE_S3_ACCESS_KEY`, `STORAGE_S3_SECRET_KEY` and the `BACKUP_S3_*`
   values. Keep `STORAGE_TYPE=local` for now.
3. Copy the existing documents. They are already encrypted, and object keys are the same paths, so no
   re-encryption is needed:
   ```bash
   set -a; eval "$(grep -E '^STORAGE_S3_(ENDPOINT|BUCKET|ACCESS_KEY|SECRET_KEY)=' .env)"; set +a
   docker run --rm -v mobility-staging_documents:/data:ro \
     -e AWS_ACCESS_KEY_ID="$STORAGE_S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$STORAGE_S3_SECRET_KEY" \
     -e AWS_DEFAULT_REGION=us-east-1 -e AWS_REQUEST_CHECKSUM_CALCULATION=when_required \
     amazon/aws-cli:2.31.0 --endpoint-url "$STORAGE_S3_ENDPOINT" \
     s3 sync /data "s3://$STORAGE_S3_BUCKET" --exclude '*.tmp'
   ```
4. Set `STORAGE_TYPE=s3` in `.env`, run `docker compose up -d`, and open an existing document
   (`GET /api/v1/admin/drivers/{id}/documents/{docId}/files/front/content`) to confirm. Keep the volume until you are
   sure; then `docker volume rm mobility-staging_documents`.
5. Schedule backups (`crontab -e` as `deploy`), 02:30 Phnom Penh time:
   ```
   30 19 * * * cd /opt/mobility && ./backup.sh >> /home/deploy/backup.log 2>&1
   ```
   Run `./backup.sh` once by hand and check that a `postgres/mobility-*.dump` object appears in the bucket.

### Failed deploy after a migration

When the deploy job ends with `Flyway applied ... new migration(s): NOT rolling back`, the new version is
not healthy and the database already has its schema. Read the core-app log tail in the job output first:

- **Fix forward** (usually best): fix the cause, merge, and deploy the new commit. The pre-deploy dump stays
  available.
- **Go back to the previous version:** restore the dump `deploy.sh` named, then deploy the previous tag:
  ```bash
  ls -lt backups/                       # newest first; the job log names the file
  docker compose stop core-app
  docker compose exec -T postgres psql -U postgres -c "DROP DATABASE mobility WITH (FORCE)" -c "CREATE DATABASE mobility OWNER mobility_app"
  docker compose exec -T postgres psql -U postgres -d mobility -c "CREATE EXTENSION postgis" -c "CREATE EXTENSION pg_trgm"
  docker compose exec -T postgres pg_restore -U postgres -d mobility --no-owner --role=mobility_app < backups/<file>.dump
  ./deploy.sh <previous full sha>      # the previous tag is in the file name and the job log
  ```
  Changes made between the dump and the restore are lost. Uploaded document files are not in the dump
  and are not touched.

### Restoring a backup

```bash
# Pick a backup: list the bucket (aws s3 ls s3://<backups-bucket>/postgres/), then download it as backup.dump.
docker compose stop core-app
docker compose exec -T postgres psql -U postgres -c "DROP DATABASE mobility WITH (FORCE)" -c "CREATE DATABASE mobility OWNER mobility_app"
docker compose exec -T postgres psql -U postgres -d mobility -c "CREATE EXTENSION postgis" -c "CREATE EXTENSION pg_trgm"
docker compose exec -T postgres pg_restore -U postgres -d mobility --no-owner --role=mobility_app < backup.dump
docker compose start core-app
```

`pg_restore` must finish without errors. (`backup.sh` leaves extension comments and PostGIS's
`spatial_ref_sys` rows out of the dump because `CREATE EXTENSION` recreates them, and restoring them as
`mobility_app` would fail.)

Restore into a scratch database first (`CREATE DATABASE restore_check`, restore there, look around, drop it)
when you only need to check something.
