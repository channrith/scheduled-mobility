#!/usr/bin/env bash
# Deploys one image tag (a git commit SHA built by CI) on the staging server:
#   ./deploy.sh <image-tag>
# 1. Dumps the database to backups/ (last 5 kept) while the current version is still running.
# 2. Pulls the app images, restarts the stack and waits for core-app, location-service, api-gateway and web-console to
#    report healthy.
# 3. Applies changed single-file config mounts (Caddyfile, RabbitMQ conf) by recreating that service.
# 4. Checks the API and the console end to end over HTTPS.
# If the apps don't become healthy, IMAGE_TAG goes back to the previous tag and that version is started
# again, unless Flyway applied new migrations: the previous version would not match the new schema, so
# the script stops and points at the pre-deploy dump (docs/staging.md, "Failed deploy after a migration").
# Used by .github/workflows/deploy-staging.yml; also safe to run by hand.
set -euo pipefail
cd "$(dirname "$0")"
# rsync copies the source folder's mode (755) onto this directory; only the deploy user needs to list it.
# Files keep their modes: containers read Caddyfile, postgres/init and rabbitmq/ as their own users.
chmod 750 .

tag=${1:?usage: ./deploy.sh <image-tag>}
timeout_seconds=${DEPLOY_TIMEOUT_SECONDS:-300}

env_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2- || true; }

set_env() {
	local tmp
	tmp=$(mktemp .env.XXXXXX)
	awk -v k="$1" -v v="$2" 'BEGIN { done = 0 }
		index($0, k "=") == 1 && !done { print k "=" v; done = 1; next }
		{ print }
		END { if (!done) print k "=" v }' .env >"$tmp"
	chmod 600 "$tmp"
	mv "$tmp" .env
}

apps=(core-app location-service api-gateway web-console)

healthy() {
	local service id status
	for service in "${apps[@]}"; do
		id=$(docker compose ps -q "$service")
		[[ -n "$id" ]] || return 1
		status=$(docker inspect -f '{{.State.Health.Status}}' "$id")
		[[ "$status" == healthy ]] || return 1
	done
}

# One time budget per start attempt, covering `docker compose up` and the health wait. `up` itself blocks
# while Caddy waits for core-app to be healthy, for as long as the Compose version decides (over 20
# minutes seen with a broken image), so it is cut off here and the health check decides. A failed deploy
# plus rollback stays within two budgets, inside the workflow's job timeout.
up_and_wait() {
	local deadline=$((SECONDS + timeout_seconds))
	timeout "$timeout_seconds" docker compose up -d --remove-orphans ||
		log "docker compose up did not finish cleanly (exit $?); checking health"
	until healthy; do
		if ((SECONDS >= deadline)); then
			return 1
		fi
		sleep 5
	done
}

log() { echo "$(date -u +%FT%TZ) $*"; }

running() { [[ -n "$(docker compose ps -q --status running "$1")" ]]; }

# Successfully applied Flyway migrations; 0 before the first start (no history table yet).
applied_migrations() {
	docker compose exec -T postgres psql -U postgres -d mobility -tAc \
		"select count(*) from public.flyway_schema_history where success" 2>/dev/null | tr -d '[:space:]' || true
}

backup_dir=backups
keep_dumps=5
dump_file=""

# Same options as backup.sh, so the dump restores as mobility_app without errors.
pre_deploy_dump() {
	if ! running postgres; then
		log "postgres is not running yet (first deploy): no pre-deploy dump"
		return
	fi
	install -d -m 700 "$backup_dir"
	dump_file="$backup_dir/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ)-${previous:0:12}.dump"
	if ! docker compose exec -T postgres pg_dump -U postgres --format=custom --no-owner --no-comments \
		--exclude-table-data=public.spatial_ref_sys mobility >"$dump_file.tmp" || [[ ! -s "$dump_file.tmp" ]]; then
		rm -f "$dump_file.tmp"
		log "pre-deploy dump failed; nothing was changed"
		exit 1
	fi
	chmod 600 "$dump_file.tmp"
	mv "$dump_file.tmp" "$dump_file"
	log "database dumped to $dump_file ($(du -h "$dump_file" | cut -f1))"
	local old
	# shellcheck disable=SC2012 # names are our own timestamps: no spaces or newlines
	for old in $(ls -1t "$backup_dir"/pre-deploy-*.dump | tail -n +$((keep_dumps + 1))); do
		rm -f "$old"
	done
}

# Single files bind-mounted into a container keep pointing at the old file after rsync replaces it, and
# Compose only recreates a service when its own definition changes. Recreate the service when the copy
# it reads differs from the file here. Entries: service, file here, path in the container.
mounted_configs=(
	"caddy Caddyfile /etc/caddy/Caddyfile"
	"rabbitmq rabbitmq/20-staging.conf /etc/rabbitmq/conf.d/20-staging.conf"
)

in_sync() { docker compose exec -T "$1" cat "$3" | cmp -s - "$2"; }

apply_mounted_configs() {
	local entry service file path
	for entry in "${mounted_configs[@]}"; do
		read -r service file path <<<"$entry"
		running "$service" || continue
		in_sync "$service" "$file" "$path" && continue
		log "$file changed; recreating $service"
		docker compose up -d --force-recreate --no-deps "$service"
		if ! in_sync "$service" "$file" "$path"; then
			log "$service still reads an old $file after recreating it; needs attention"
			return 1
		fi
	done
}

previous=$(env_value IMAGE_TAG)
log "deploying $tag (previous: ${previous:-none})"
# Before touching anything: every value compose.yml requires must be in .env (e.g. a newly added host).
if ! config_errors=$(IMAGE_TAG="$tag" docker compose config -q 2>&1); then
	log "compose configuration is incomplete; nothing was changed:"
	echo "$config_errors"
	exit 1
fi
migrations_before=$(applied_migrations)
migrations_before=${migrations_before:-0}
pre_deploy_dump
set_env IMAGE_TAG "$tag"

# Pull first: a missing image fails here, before anything running is touched.
if ! docker compose pull --quiet "${apps[@]}"; then
	log "pull failed; keeping ${previous:-the current version}"
	set_env IMAGE_TAG "$previous"
	exit 1
fi
if ! up_and_wait; then
	log "not healthy after ${timeout_seconds}s:"
	docker compose ps --format "table {{.Service}}\t{{.Status}}" "${apps[@]}" || true
	log "core-app log tail:"
	docker compose logs --tail 40 core-app || true
	migrations_after=$(applied_migrations)
	if ((${migrations_after:-0} > migrations_before)); then
		log "Flyway applied $((migrations_after - migrations_before)) new migration(s): NOT rolling back,"
		log "because $previous does not match the new schema. Fix forward with a new deploy, or restore"
		log "${dump_file:-the pre-deploy dump} and then deploy $previous (docs/staging.md, \"Failed deploy after a migration\")."
		exit 1
	fi
	if [[ -n "$previous" && "$previous" != "$tag" ]]; then
		log "rolling back to $previous"
		set_env IMAGE_TAG "$previous"
		if up_and_wait; then
			log "rolled back to $previous"
		else
			log "rollback is not healthy either; needs attention"
		fi
	fi
	exit 1
fi
log "healthy"
apply_mounted_configs

# End to end through Caddy and TLS. Certificates can take a minute on the very first deploy (or for a
# newly added host).
api_url="https://$(env_value API_HOST)/actuator/health"
console_url="https://$(env_value CONSOLE_HOST)/login"
for _ in $(seq 1 24); do
	if curl -fsS --max-time 5 "$api_url" >/dev/null && curl -fsS --max-time 5 -o /dev/null "$console_url"; then
		log "$api_url and $console_url are up"
		docker image prune -f >/dev/null
		exit 0
	fi
	sleep 5
done
log "apps are healthy but $api_url or $console_url does not answer; check: docker compose logs caddy"
exit 1
