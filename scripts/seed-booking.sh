#!/usr/bin/env bash
# Seed only local Compose infrastructure. Leaves existing IDE services running.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p var/seed-booking
set -a
source .env
set +a

# Explicit loopback connections; do not inherit deployment connection URLs.
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DB_PORT:-5432}/${DB_NAME:-mobility}"
export SPRING_DATASOURCE_USERNAME="${DB_USERNAME:-mobility}"
export SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD:-mobility}"
export SPRING_DATA_REDIS_HOST=127.0.0.1
export SPRING_RABBITMQ_HOST=127.0.0.1

psql_local() {
  docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}
# Do not grant driver privileges or repair a real account accidentally using these test phone numbers.
psql_local <<'SQL'
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM identity.users
             WHERE phone_e164 IN ('+85510555801','+85510555802')
             AND full_name IS DISTINCT FROM 'Local Test Driver 0' || substring(phone_e164 FROM 5)) THEN
    RAISE EXCEPTION 'A reserved fixture phone is occupied by a different account';
  END IF;
END $$;
SQL

./mvnw -q -pl core-app -am package -DskipTests > var/seed-booking/build.log 2>&1
seed_pid=''
cleanup() {
  if [[ -n "$seed_pid" ]]; then
    kill "$seed_pid" 2>/dev/null || true
    wait "$seed_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM
for phone in 010555801 010555802; do
  log="var/seed-booking/driver-$phone.log"
  LOCAL_APPROVED_DRIVER_SEED_ENABLED=true LOCAL_APPROVED_DRIVER_SEED_PHONE="$phone" \
    java -jar core-app/target/core-app-0.0.1-SNAPSHOT.jar \
      --spring.profiles.active=local --server.address=127.0.0.1 --server.port=0 \
      --scheduling.enabled=false --dispatch.automatic.enabled=false \
      --dispatch.automatic.listener-enabled=false \
      --identity.token.allow-generated-keys=true --identity.bootstrap.admin-phone= > "$log" 2>&1 &
  seed_pid=$!
  ready=false
  for attempt in $(seq 1 90); do
    if grep -q 'Local APPROVED driver fixture ready' "$log"; then ready=true; break; fi
    if ! kill -0 "$seed_pid" 2>/dev/null; then break; fi
    sleep 1
  done
  cleanup
  seed_pid=''
  if [[ "$ready" != true ]]; then
    echo "Fixture startup failed; inspect $log" >&2
    exit 1
  fi
  echo "Approved local driver fixture ready: $phone"
done
./scripts/seed-pricing.sh
psql_local < scripts/seed/booking.sql
psql_local <<'SQL'
SELECT d.id AS driver_id,d.full_name,d.status,v.plate_number,v.vehicle_class,
       count(DISTINCT s.id) AS upcoming_shifts
FROM driver.drivers d
JOIN driver.driver_vehicle_assignments a ON a.driver_id=d.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
JOIN driver.vehicles v ON v.id=a.vehicle_id
LEFT JOIN dispatch.shift_blocks s ON s.driver_id=d.id AND s.status='ACTIVE' AND s.starts_at>now()
WHERE d.phone_e164 IN ('+85510555801','+85510555802')
GROUP BY d.id,d.full_name,d.status,v.plate_number,v.vehicle_class;
SELECT version,description FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;
SQL
