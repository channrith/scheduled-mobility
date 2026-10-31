#!/usr/bin/env bash
# Start all backends using the repository's local environment.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "Missing $ROOT/.env. Copy .env.example to .env and configure JWT keys first." >&2
  exit 1
fi

set -a
source .env
set +a

# Spring Resource paths are resolved against each application's working directory.
if [[ -z "${JWT_PUBLIC_KEY_LOCATION:-}" && -n "${JWT_PUBLIC_KEY_FILE:-}" ]]; then
  export JWT_PUBLIC_KEY_LOCATION="file:${JWT_PUBLIC_KEY_FILE}"
fi

export CORE_BASE_URL="http://localhost:${CORE_SERVER_PORT:-${SERVER_PORT:-8080}}"
export CORE_APP_URL="$CORE_BASE_URL"
export LOCATION_SERVICE_URL="http://localhost:${LOCATION_SERVER_PORT:-8081}"

modules=(core-app location-service api-gateway)
ports=("${CORE_SERVER_PORT:-${SERVER_PORT:-8080}}" "${LOCATION_SERVER_PORT:-8081}" "${GATEWAY_SERVER_PORT:-8090}")
for i in "${!modules[@]}"; do
  if command -v lsof >/dev/null 2>&1; then
    listeners="$(lsof -nP -iTCP:"${ports[$i]}" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$listeners" ]]; then
      echo "Cannot start ${modules[$i]}: port ${ports[$i]} is already in use." >&2
      echo "$listeners" >&2
      echo "Stop the existing service, then run this script again." >&2
      echo "Or choose other ports with CORE_SERVER_PORT, LOCATION_SERVER_PORT and GATEWAY_SERVER_PORT." >&2
      exit 1
    fi
  fi
done

mkdir -p var/log
# Give each Maven process and its application a process group for clean shutdown.
set -m
pids=()
cleanup() {
  trap - EXIT INT TERM
  for pid in "${pids[@]}"; do
    kill -TERM -- "-$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for i in "${!modules[@]}"; do
  module="${modules[$i]}"
  SERVER_PORT="${ports[$i]}" ./mvnw spring-boot:run -pl "$module" \
    -Dspring-boot.run.profiles=local \
    "-Dspring-boot.run.workingDirectory=$ROOT" \
    > "var/log/$module.log" 2>&1 &
  pids+=("$!")
done

echo "Starting core-app (:${ports[0]}), location-service (:${ports[1]}) and gateway (:${ports[2]})."
echo "Logs: $ROOT/var/log/{core-app,location-service,api-gateway}.log"
echo "Press Ctrl+C to stop all services."

while true; do
  for i in "${!pids[@]}"; do
    pid="${pids[$i]}"
    if ! kill -0 "$pid" 2>/dev/null; then
      status=0
      wait "$pid" || status=$?
      echo "${modules[$i]} exited (status $status); stopping all services." >&2
      tail -n 60 "var/log/${modules[$i]}.log" >&2
      exit "$status"
    fi
  done
  sleep 1
done
