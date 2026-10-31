#!/usr/bin/env bash
# Local development stack: infrastructure in Docker, the Java services (and optionally the console) as local
# processes, the same way README "Run locally" describes.
#
#   scripts/dev.sh up [--console]   start infrastructure, core-app, location-service, api-gateway (+ console)
#   scripts/dev.sh status           what is running and healthy
#   scripts/dev.sh smoke            the gateway's token check and routes; exits 1 if one fails
#   scripts/dev.sh down             stop the processes this script started (infrastructure keeps running)
#   scripts/dev.sh logs <service>   follow a service's log (core-app, location-service, api-gateway, web-console)
#
# Logs and PID files live in var/dev/ (git-ignored). Only processes started by this script are stopped.
# Works with macOS's bash 3.2.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="$ROOT/var/dev"
SERVICES="core-app location-service api-gateway"
HEALTH_TIMEOUT=${DEV_HEALTH_TIMEOUT:-240}

port_of() {
	case "$1" in
		core-app) echo 8080 ;;
		location-service) echo 8081 ;;
		api-gateway) echo 8090 ;;
		web-console) echo 3000 ;;
	esac
}

health_url() {
	case "$1" in
		web-console) echo "http://localhost:3000/login" ;;
		*) echo "http://localhost:$(port_of "$1")/actuator/health" ;;
	esac
}

say() { printf '%s\n' "$*"; }
fail() { printf 'dev.sh: %s\n' "$*" >&2; exit 1; }

listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }

http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1" 2>/dev/null || true; }

# The process and all its descendants (mvnw forks the JVM; npm forks next).
tree() {
	local pid=$1 child
	echo "$pid"
	for child in $(pgrep -P "$pid" 2>/dev/null || true); do
		tree "$child"
	done
}

# Asks the whole tree to stop, waits up to 15 s (Maven takes a few seconds), then force-stops what is left.
stop_tree() {
	local pids pid alive i
	pids=$(tree "$1")
	kill $pids 2>/dev/null || true
	for i in $(seq 1 15); do
		alive=""
		for pid in $pids; do
			kill -0 "$pid" 2>/dev/null && alive="$alive $pid"
		done
		[[ -z "$alive" ]] && return 0
		sleep 1
	done
	kill -9 $alive 2>/dev/null || true
}

check_env() {
	[[ -f "$ROOT/.env" ]] || fail ".env is missing: cp .env.example .env (README, Run locally)"
	local key
	for key in JWT_PRIVATE_KEY_FILE JWT_PUBLIC_KEY_FILE; do
		local value
		value=$(grep -E "^$key=" "$ROOT/.env" | tail -1 | cut -d= -f2- || true)
		# Optional: without key files core-app generates a throwaway pair, and the other services fetch it from its JWKS.
		[[ -z "$value" || -f "$ROOT/${value#./}" || -f "$value" ]] \
			|| fail "$key points to $value, which does not exist: generate the keys or empty the setting (README, Run locally)"
	done
}

start() {
	local service=$1 port log
	port=$(port_of "$service")
	log="$STATE/$service.log"
	if [[ -n "$(listening "$port")" ]]; then
		say "  $service: port $port already in use (pid $(listening "$port")); not starting another"
		return
	fi
	case "$service" in
		core-app)
			(cd "$ROOT" && set -a && . ./.env && set +a \
				&& exec ./mvnw -B -ntp spring-boot:run -pl core-app -Dspring-boot.run.profiles=local) >"$log" 2>&1 &
			;;
		location-service)
			(cd "$ROOT" && set -a && . ./.env && set +a && exec ./mvnw -B -ntp spring-boot:run -pl location-service) >"$log" 2>&1 &
			;;
		api-gateway)
			# Needs no .env: every setting has a local default.
			(cd "$ROOT" && exec ./mvnw -B -ntp spring-boot:run -pl api-gateway) >"$log" 2>&1 &
			;;
		web-console)
			[[ -d "$ROOT/web-console/node_modules" ]] || fail "web-console/node_modules is missing: cd web-console && npm install"
			(cd "$ROOT/web-console" && exec npm run dev) >"$log" 2>&1 &
			;;
	esac
	echo $! >"$STATE/$service.pid"
	say "  $service: starting (log: var/dev/$service.log)"
}

wait_healthy() {
	local service=$1 url deadline code
	url=$(health_url "$service")
	deadline=$((SECONDS + HEALTH_TIMEOUT))
	while ((SECONDS < deadline)); do
		code=$(http_code "$url")
		if [[ "$code" == 200 ]]; then
			say "  $service: healthy ($url)"
			return 0
		fi
		if [[ -f "$STATE/$service.pid" ]] && ! kill -0 "$(cat "$STATE/$service.pid")" 2>/dev/null; then
			say "  $service: EXITED. Last log lines:"
			grep -E 'APPLICATION FAILED|Description:|Caused by|ERROR' -A2 "$STATE/$service.log" | tail -12 | sed 's/^/    /'
			return 1
		fi
		sleep 2
	done
	say "  $service: not healthy after ${HEALTH_TIMEOUT}s (see var/dev/$service.log)"
	return 1
}

cmd_up() {
	local with_console=false
	[[ "${1:-}" == "--console" ]] && with_console=true
	check_env
	mkdir -p "$STATE"
	command -v docker >/dev/null || fail "docker is not installed or not on PATH"
	docker info >/dev/null 2>&1 || fail "Docker is not running"
	say "Infrastructure (postgres, redis, rabbitmq):"
	# Named services: a plain `up -d` also starts the Dockerised console on port 3000.
	local out
	out=$(cd "$ROOT" && docker compose up -d --wait postgres redis rabbitmq 2>&1) || { say "$out"; fail "docker compose up failed"; }
	say "  healthy"
	if $with_console && [[ -n "$(docker ps -q -f name=mobility-web-console 2>/dev/null)" ]]; then
		fail "the Dockerised console holds port 3000: docker compose stop web-console"
	fi
	say "Starting:"
	local service all="$SERVICES"
	$with_console && all="$all web-console"
	for service in $all; do
		start "$service"
	done
	say "Waiting for health (up to ${HEALTH_TIMEOUT}s each):"
	local failed=0
	for service in $all; do
		wait_healthy "$service" || failed=1
	done
	if ((failed)); then
		say "Some services did not start. Fix and run 'scripts/dev.sh up' again (healthy ones are kept), or 'scripts/dev.sh down'."
		exit 1
	fi
	say "Up. Gateway: http://localhost:8090   Swagger: http://localhost:8080/swagger-ui.html$($with_console && echo '   Console: http://localhost:3000')"
	say "Stop with: scripts/dev.sh down"
}

cmd_status() {
	local service port pid code owner
	printf '%-17s %-6s %-8s %-7s %s\n' SERVICE PORT PID HEALTH STARTED-BY
	for service in $SERVICES web-console; do
		port=$(port_of "$service")
		pid=$(listening "$port")
		code=$(http_code "$(health_url "$service")")
		owner="-"
		if [[ -n "$pid" ]]; then
			owner="other"
			[[ -f "$STATE/$service.pid" ]] && kill -0 "$(cat "$STATE/$service.pid")" 2>/dev/null && owner="dev.sh"
		fi
		printf '%-17s %-6s %-8s %-7s %s\n' "$service" "$port" "${pid:--}" "$([[ "$code" == 200 ]] && echo up || echo down)" "$owner"
	done
	(cd "$ROOT" && docker compose ps --format 'table {{.Service}}\t{{.Status}}' 2>/dev/null) || true
}

cmd_down() {
	local service pidfile stopped=0
	for service in web-console $SERVICES; do
		pidfile="$STATE/$service.pid"
		[[ -f "$pidfile" ]] || continue
		if kill -0 "$(cat "$pidfile")" 2>/dev/null; then
			stop_tree "$(cat "$pidfile")"
			say "  $service: stopped"
			stopped=1
		fi
		rm -f "$pidfile"
	done
	((stopped)) || say "  nothing started by dev.sh is running"
	for service in $SERVICES web-console; do
		local pid
		pid=$(listening "$(port_of "$service")")
		[[ -n "$pid" ]] && say "  note: port $(port_of "$service") is still in use by pid $pid (not started by dev.sh; left alone)"
	done
	say "Infrastructure keeps running; stop it with: docker compose down"
}

cmd_smoke() {
	local failed=0 body code headers
	check() { # name, expected, actual
		if [[ "$2" == "$3" ]]; then say "  ok    $1"; else say "  FAIL  $1: expected $2, got $3"; failed=1; fi
	}
	code_of() { printf '%s' "$1" | sed -nE 's/.*"code":"([^"]+)".*/\1/p'; }
	local GW=http://localhost:8090
	say "Health:"
	for service in $SERVICES; do
		check "$service health" 200 "$(http_code "$(health_url "$service")")"
	done
	say "Token check at the gateway:"
	headers=$(curl -s -D - -o /dev/null --max-time 5 -H 'X-Request-Id: smoke-test-0001' $GW/api/v1/me | tr -d '\r' || true)
	check "GET /api/v1/me without a token: 401" 401 "$(printf '%s' "$headers" | awk 'NR==1{print $2}')"
	check "  with WWW-Authenticate: Bearer" Bearer "$(printf '%s' "$headers" | awk -F': ' 'tolower($1)=="www-authenticate"{print $2}')"
	check "  and the X-Request-Id back" smoke-test-0001 "$(printf '%s' "$headers" | awk -F': ' 'tolower($1)=="x-request-id"{print $2}')"
	check "  as problem+json, code auth.unauthorized" auth.unauthorized "$(code_of "$(curl -s --max-time 5 $GW/api/v1/me || true)")"
	say "Routing:"
	body=$(curl -s --max-time 5 -X POST -H 'Content-Type: application/json' -d '{}' $GW/api/v1/auth/otp/request || true)
	check "public route reaches core-app (its own 400 'validation')" validation "$(code_of "$body")"
	check "unknown path: the gateway's own 404" gateway.not-found "$(code_of "$(curl -s --max-time 5 $GW/nothing-here || true)")"
	check "backend actuator not exposed" 404 "$(http_code $GW/actuator/env)"
	# With a token (needs OTP_FIXED_CODE in .env; each run signs in a new local test phone).
	local fixed phone token
	fixed=$(grep -E '^OTP_FIXED_CODE=' "$ROOT/.env" 2>/dev/null | tail -1 | cut -d= -f2- || true)
	if [[ -n "$fixed" ]]; then
		phone="0129$(printf '%05d' $((RANDOM % 100000)))"
		curl -s -o /dev/null --max-time 5 -X POST -H 'Content-Type: application/json' -d "{\"phone\":\"$phone\"}" $GW/api/v1/auth/otp/request || true
		token=$(curl -s --max-time 5 -X POST -H 'Content-Type: application/json' -d "{\"phone\":\"$phone\",\"code\":\"$fixed\"}" \
			$GW/api/v1/auth/otp/verify | sed -nE 's/.*"accessToken":"([^"]+)".*/\1/p' || true)
		say "Signed in (test phone $phone):"
		check "GET /api/v1/me with the token: core-app answers 200" 200 \
			"$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H "Authorization: Bearer $token" $GW/api/v1/me || true)"
		check "passenger ping reaches location-service (its own 403: drivers only)" 403 \
			"$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
				-d '{"latitude":11.57,"longitude":104.92}' $GW/api/v1/locations || true)"
	else
		say "  skip  checks with a token: set OTP_FIXED_CODE in .env to run them"
	fi
	if [[ -n "$(listening 3000)" ]]; then
		say "Console:"
		check "GET /login" 200 "$(http_code http://localhost:3000/login)"
	fi
	if ((failed)); then
		say "Smoke test FAILED"
		exit 1
	fi
	say "Smoke test passed"
}

cmd_logs() {
	local service=${1:-}
	[[ -n "$service" && -f "$STATE/$service.log" ]] || fail "usage: scripts/dev.sh logs <core-app|location-service|api-gateway|web-console> (no log yet for '$service')"
	tail -n 50 -f "$STATE/$service.log"
}

case "${1:-}" in
	up) shift; cmd_up "$@" ;;
	status) cmd_status ;;
	smoke) cmd_smoke ;;
	down) cmd_down ;;
	logs) shift; cmd_logs "$@" ;;
	*) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
