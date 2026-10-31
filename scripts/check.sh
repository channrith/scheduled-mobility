#!/usr/bin/env bash
# Checks to run before a commit: the same as CI plus the repo's own pitfalls. Cheap checks first; stops at the
# first failure and says what to do.
#
#   scripts/check.sh                 everything (backend tests need Docker, ~3-4 min)
#   scripts/check.sh --fast          skip the integration tests (*IT); unit tests still run
#   scripts/check.sh --no-console    skip the web-console checks
#   scripts/check.sh --no-backend    skip ./mvnw verify
#   scripts/check.sh --allow-deletions   accept deleted tracked files (an intended removal)
#
# Works with macOS's bash 3.2.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fast=false console=true backend=true allow_deletions=false
for arg in "$@"; do
	case "$arg" in
		--fast) fast=true ;;
		--no-console) console=false ;;
		--no-backend) backend=false ;;
		--allow-deletions) allow_deletions=true ;;
		*) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
	esac
done

LOG_DIR="$ROOT/var/check"
mkdir -p "$LOG_DIR"
step=0
started=$SECONDS

begin() { step=$((step + 1)); printf '%2d. %-62s' "$step" "$1"; }
pass() { printf 'ok%s\n' "${1:+  ($1)}"; }
fail() {
	printf 'FAILED\n\n%s\n' "$1" >&2
	exit 1
}
# Runs a command quietly; on failure shows the end of its output (full log in var/check/).
run() { # name, command...
	local name=$1 log
	shift
	log="$LOG_DIR/$name.log"
	if "$@" >"$log" 2>&1; then
		return 0
	fi
	tail -n 40 "$log" >&2
	printf '\n(full output: var/check/%s.log)\n' "$name" >&2
	return 1
}

# 1. Deleted tracked files: both GitHub workflow files were once deleted by accident and nearly committed.
begin "no tracked files deleted by accident"
deleted=$(git status --porcelain | awk '$1 ~ /D/ {print $NF}')
if [[ -n "$deleted" ]] && ! $allow_deletions; then
	fail "These tracked files are deleted in the working tree or index:
$(printf '%s\n' "$deleted" | sed 's/^/  /')
Restore them with: git restore --staged <file> && git restore <file>
If the removal is intended, run again with --allow-deletions."
fi
pass "${deleted:+deletions allowed}"

# 2. Every Maven module can reach the Docker build context (.dockerignore is an allowlist).
begin ".dockerignore lets every Maven module into the image build"
missing=""
for module in $(sed -nE 's#.*<module>([^<]+)</module>.*#\1#p' pom.xml); do
	for entry in "!$module/pom.xml" "!$module/src/main/"; do
		grep -qxF "$entry" .dockerignore || missing="$missing $entry"
	done
done
[[ -z "$missing" ]] || fail "Add these lines to .dockerignore, or the module's image build fails (\"Child module ... does not exist\"):
$(printf '  %s\n' $missing)"
pass

# 3. Java record names are unique: springdoc names OpenAPI schemas after them, so a duplicate silently merges two.
begin "record names unique for OpenAPI (Entry, Money may repeat)"
dupes=$(grep -rhoE '\brecord [A-Z][A-Za-z0-9]+' core-app/src/main/java | sort | uniq -d | grep -vE 'record (Entry|Money)$' || true)
[[ -z "$dupes" ]] || fail "Duplicate record names (rename one, e.g. DriverSummary -> DriverInfo):
$(printf '%s\n' "$dupes" | sed 's/^/  /')"
pass

if $console; then
	[[ -d web-console/node_modules ]] || fail "web-console/node_modules is missing: cd web-console && npm install"

	# 4. API types match the OpenAPI snapshot (CI runs the same).
	# Compares before and after, not against git: during an API change both files are legitimately modified.
	begin "web-console API types in sync with docs/openapi.json"
	before=$(shasum web-console/src/lib/api/schema.ts | cut -d' ' -f1)
	run gen-api bash -c 'cd web-console && npm run gen:api' || fail "npm run gen:api failed"
	[[ "$(shasum web-console/src/lib/api/schema.ts | cut -d' ' -f1)" == "$before" ]] || fail "web-console/src/lib/api/schema.ts was out of date with docs/openapi.json and has just been regenerated.
Review it (git diff web-console/src/lib/api/schema.ts) and commit it with the API change."
	pass

	begin "web-console lint"
	run lint bash -c 'cd web-console && npm run lint' || fail "Fix the lint errors above (cd web-console && npm run lint)."
	pass

	begin "web-console typecheck"
	run typecheck bash -c 'cd web-console && npm run typecheck' || fail "Fix the type errors above (cd web-console && npm run typecheck)."
	pass

	begin "web-console build"
	run build bash -c 'cd web-console && NEXT_TELEMETRY_DISABLED=1 npm run build' || fail "The production build failed (cd web-console && npm run build)."
	pass
fi

if $backend; then
	begin "backend: ./mvnw verify$($fast && echo ' (unit tests only)')"
	if ! $fast; then
		docker info >/dev/null 2>&1 || fail "Docker is not running; integration tests use Testcontainers. Start Docker, or use --fast."
	fi
	t=$SECONDS
	if $fast; then
		run maven ./mvnw -B -ntp verify -DskipITs || fail "Backend build or tests failed. Test reports: */target/surefire-reports/ and */target/failsafe-reports/."
	else
		run maven ./mvnw -B -ntp verify || fail "Backend build or tests failed. Test reports: */target/surefire-reports/ and */target/failsafe-reports/.
If OpenApiSnapshotIT failed after an intended API change: ./mvnw verify -Dopenapi.update=true, review and commit
docs/openapi.json, then run this again (it regenerates web-console's API types)."
	fi
	summary=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: 0, Errors: 0' "$LOG_DIR/maven.log" | grep -v ' in ' | sed -E 's/.*Tests run: ([0-9]+).*/\1/' | paste -sd+ - | bc 2>/dev/null || echo "?")
	pass "$summary tests, $((SECONDS - t))s"
fi

printf '\nAll checks passed in %ss.\n' "$((SECONDS - started))"
