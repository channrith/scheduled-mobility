#!/usr/bin/env bash
# Local development stack: infrastructure in Docker, the Java services (and optionally the console) as local
# processes, the same way README "Run locally" describes.
#
#   scripts/dev.sh up [--console]   start infrastructure, core-app, location-service, api-gateway (+ console)
#   scripts/dev.sh status           what is running and healthy
#   scripts/dev.sh smoke            requests through the gateway; exits 1 if one fails
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
		[[ -n "$value" ]] || fail "$key is empty in .env; location-service cannot check core-app's tokens without shared keys (README, Run locally, step 3)"
		[[ -f "$ROOT/${value#./}" || -f "$value" ]] || fail "$key points to $value, which does not exist (generate the keys: README, Run locally, step 2)"
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
	say "Building shared platform-security library:"
	if ! (cd "$ROOT" && ./mvnw -B -ntp -pl platform-security -am install -DskipTests) >"$STATE/platform-security-build.log" 2>&1; then
		tail -n 60 "$STATE/platform-security-build.log" >&2
		fail "shared library build failed"
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
	say "Health:"
	for service in $SERVICES; do
		check "$service health" 200 "$(http_code "$(health_url "$service")")"
	done
	say "Through the gateway (http://localhost:8090):"
	headers=$(curl -s -D - -o /dev/null --max-time 5 -H 'X-Request-Id: smoke-test-0001' http://localhost:8090/api/v1/me || true)
	check "GET /api/v1/me reaches core-app (401 without a token)" 401 "$(printf '%s' "$headers" | awk 'NR==1{print $2}')"
	check "X-Request-Id comes back" smoke-test-0001 "$(printf '%s' "$headers" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-request-id"{print $2}')"
	body=$(curl -s --max-time 5 http://localhost:8090/api/v1/me || true)
	check "core-app's problem+json (code auth.unauthorized)" auth.unauthorized "$(printf '%s' "$body" | sed -nE 's/.*"code":"([^"]+)".*/\1/p')"
	code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST http://localhost:8090/api/v1/locations -H 'Content-Type: application/json' -d '{}' || true)
	check "POST /api/v1/locations reaches location-service (401 without a token)" 401 "$code"
	body=$(curl -s --max-time 5 -H 'Accept-Language: en' http://localhost:8090/nothing-here || true)
	check "unknown path: the gateway's own 404" gateway.not-found "$(printf '%s' "$body" | sed -nE 's/.*"code":"([^"]+)".*/\1/p')"
	check "backend actuator not exposed" 404 "$(http_code http://localhost:8090/actuator/env)"
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
