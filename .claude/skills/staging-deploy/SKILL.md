---
name: staging-deploy
description: Guide a deploy of the mobility platform to the staging droplet (GitHub Actions "Deploy staging" + deploy/staging/deploy.sh), including the pre-checks that prevent broken or unrecoverable deploys, the commands the user runs, and how to read the job log. Use this whenever the user wants to deploy, ship or release to staging, asks whether a commit is safe to deploy, adds a setting to deploy/staging/compose.yml or .env, merges a Flyway migration that will reach staging, or pastes output from the Deploy staging job, even if they don't say "deploy".
---

# Deploying to staging

Staging is one DigitalOcean droplet running Docker Compose in `/opt/mobility` (see `docs/staging.md`, the
runbook, for the full picture). A deploy = **Actions → Deploy staging → Run workflow** for a commit on `main`; the
job syncs `deploy/staging/` to the server and runs `deploy.sh <full sha>`.

**Who does what:** the user runs every command on the droplet and in GitHub and pastes the output; you give
the commands, say what output to expect, and check what comes back. Never ask for `.env` contents or private
keys, only for the specific non-secret lines a command prints.

## 1. Pre-checks (before the user presses Run workflow)

Do these from the repository; each one catches a failure mode that already happened once.

1. **CI is green for the exact commit**, including the `images` job (it pushes one image per app in its
   matrix). Public repo, so this works without auth:
   `curl -s "https://api.github.com/repos/channrith/scheduled-mobility/actions/runs?branch=main&per_page=3"`
   and check `head_sha` / `conclusion`. The deploy job refuses a commit without images anyway, but it is
   quicker to know first.
2. **New required settings.** List what `compose.yml` requires (`grep -oE '\$\{[A-Z_]+:\?' deploy/staging/compose.yml`)
   and diff against what the previous deployed commit required
   (`git diff <deployed sha> HEAD -- deploy/staging/compose.yml deploy/staging/.env.staging.example`).
   Any new `${NAME:?}` or new setting with a non-default value must be added to the droplet's `.env`
   **before** the deploy. Give the user the exact line, e.g.
   `echo 'CONSOLE_HOST=console.146-190-4-99.sslip.io' >> .env`. Also check settings whose **default changed**
   in `compose.yml` but which the droplet's `.env` sets explicitly (it was created from the example file),
   e.g. a memory limit: the `.env` value wins, so it needs a `sed` too.
   (`deploy.sh` stops with "compose configuration is incomplete" if a required value is missing; that is
   safe but wastes a run.)
3. **Destructive migrations.** Find migrations added since the deployed commit
   (`git diff --name-only <deployed sha> HEAD -- core-app/src/main/resources/db/migration`) and grep them for
   `DROP`, `RENAME`, `DELETE`, `ALTER COLUMN ... TYPE`, constraint changes. If any:
   - tell the user that **automatic rollback is off for this deploy**: once Flyway applies them, the old
     version fails Hibernate validation, so `deploy.sh` stops instead of rolling back;
   - have them take a **manual dump first** and copy it off the droplet (commands below);
   - suggest expand/contract for next time (add new column, write both, drop old one in a later release).
4. **What is deployed now:** ask the user for `grep '^IMAGE_TAG=' /opt/mobility/.env` (or use the
   `staging-check` skill) so step 2 and 3 compare against the right commit.

Manual dump (on the server as `deploy`, in `/opt/mobility`; `manual-*` files are never auto-deleted):

```bash
cd /opt/mobility
docker compose exec -T postgres pg_dump -U postgres --format=custom --no-owner --no-comments \
  --exclude-table-data=public.spatial_ref_sys mobility > backups/manual-$(date -u +%Y%m%dT%H%M%SZ).dump
chmod 600 backups/manual-*.dump && ls -l backups
```

Then on the user's Mac: `scp 'root@146.190.4.99:/opt/mobility/backups/manual-*.dump' ~/`.
A 0-byte file means the dump failed (usually: not in `/opt/mobility`).

## 2. Run

GitHub → Actions → **Deploy staging** → Run workflow, branch `main`, `image_tag` empty (= that commit) or a
full 40-character SHA. The `staging` environment may ask for approval.

## 3. Read the Deploy step log

Ask for the lines starting with a timestamp in the **Deploy** step. Normal sequence:

| Line | Meaning |
|---|---|
| `deploying <sha> (previous: <sha>)` | started |
| `database dumped to backups/pre-deploy-...dump (48K)` | safety dump taken (last 5 kept) |
| `healthy` | every app in `deploy.sh`'s `apps=(...)` passed its health check |
| `Caddyfile changed; recreating caddy` | expected whenever the Caddyfile changed in this deploy |
| `https://<api>/actuator/health and https://<console>/login are up` | done |

Harmless: `***` inside words (GitHub masks the secret value `deploy`), Docker's "credentials are stored
unencrypted" warning (the short-lived token is logged out after the pull), and a few
`curl: (35) ... tlsv1 alert internal error` lines while Caddy obtains a certificate for a new host.

Failures:

| Log | What happened | Next |
|---|---|---|
| `compose configuration is incomplete` + a variable name | `.env` lacks it; nothing changed | add it (pre-check 2), run again |
| `pull failed; keeping <old>` | image missing | check CI for that SHA |
| `not healthy after 300s` + `rolled back to <old>` | broken version, no new migrations; old version back | read the core-app log tail in the job, fix forward |
| `Flyway applied N new migration(s): NOT rolling back` | schema already changed | fix forward (preferred), or restore the named dump and deploy the previous SHA: `docs/staging.md`, "Failed deploy after a migration" |
| `... does not answer; check: docker compose logs caddy` | apps fine, TLS/routing not | ask for `docker compose logs --tail 50 caddy` |

## 4. Verify

Run the read-only checks from the `staging-check` skill, plus anything specific to the change (a new
endpoint, a raised limit). For HTTP checks you can curl the public hosts yourself: they are public, and
read-only GETs are fine. Anything that writes data (registering a test driver, uploading) belongs to the user
or needs their go-ahead, and uses made-up data only: staging is **test data only** (fixed login code, no
off-site backups).

## When the deploy setup itself changed

If `deploy.sh`, `compose.yml` or the `Caddyfile` changed in the commit, rehearse it locally first with the
`deploy-rehearsal` skill. Remember the single-file bind mounts: a changed config file only reaches a running
container because `deploy.sh` recreates that service.
