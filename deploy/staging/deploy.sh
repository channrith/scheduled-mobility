#!/usr/bin/env bash
# Deploys one image tag (a git commit SHA built by CI) on the staging server:
#   ./deploy.sh <image-tag>
# Pulls both app images, restarts the stack and waits for core-app and location-service to report
# healthy. If they don't within the timeout, IMAGE_TAG is set back to the previous tag and that version
# is started again. Used by .github/workflows/deploy-staging.yml; also safe to run by hand.
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

healthy() {
	local service id status
	for service in core-app location-service; do
		id=$(docker compose ps -q "$service")
		[[ -n "$id" ]] || return 1
		status=$(docker inspect -f '{{.State.Health.Status}}' "$id")
		[[ "$status" == healthy ]] || return 1
	done
}

wait_healthy() {
	local deadline=$((SECONDS + timeout_seconds))
	until healthy; do
		if ((SECONDS >= deadline)); then
			return 1
		fi
		sleep 5
	done
}

log() { echo "$(date -u +%FT%TZ) $*"; }

previous=$(env_value IMAGE_TAG)
log "deploying $tag (previous: ${previous:-none})"
set_env IMAGE_TAG "$tag"

# Pull first: a missing image fails here, before anything running is touched.
if ! docker compose pull --quiet core-app location-service; then
	log "pull failed; keeping ${previous:-the current version}"
	set_env IMAGE_TAG "$previous"
	exit 1
fi
# `up` itself fails when a service never becomes healthy (Caddy waits for core-app). That must not end
# the script under `set -e`: the health check below decides, and rolls back if needed.
up() { docker compose up -d --remove-orphans || log "docker compose up reported a failure"; }
up

if ! wait_healthy; then
	log "not healthy after ${timeout_seconds}s; core-app log tail:"
	docker compose logs --tail 40 core-app || true
	if [[ -n "$previous" && "$previous" != "$tag" ]]; then
		log "rolling back to $previous"
		set_env IMAGE_TAG "$previous"
		up
		if wait_healthy; then
			log "rolled back to $previous"
		else
			log "rollback is not healthy either; needs attention"
		fi
	fi
	exit 1
fi
log "healthy"

# End to end through Caddy and TLS. Certificates can take a minute on the very first deploy.
api_host=$(env_value API_HOST)
for _ in $(seq 1 12); do
	if curl -fsS --max-time 5 "https://$api_host/actuator/health" >/dev/null; then
		log "https://$api_host is up"
		docker image prune -f >/dev/null
		exit 0
	fi
	sleep 5
done
log "apps are healthy but https://$api_host does not answer; check: docker compose logs caddy"
exit 1
