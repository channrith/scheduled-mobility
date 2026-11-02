---
name: spring-upgrade
description: Upgrade Spring Boot in the mobility backend together with the versions that must match it (Spring Cloud for api-gateway, Spring Modulith, springdoc), check compatibility first, verify with scripts/check.sh, the OpenAPI snapshot, the local stack smoke test and a deploy rehearsal, and roll back cleanly if it fails. Use this whenever the user wants to upgrade, bump or update Spring Boot, Spring Cloud, Spring Modulith or springdoc, asks whether a newer Spring version is available or safe, sees a Boot/Cloud compatibility error ("Spring Boot [x] is not compatible with this Spring Cloud release train"), or asks what version the project is on, even if they only say "update dependencies".
---

# Upgrading Spring Boot and its companions

The version is one line; the work is everything that has to move with it. Follow CLAUDE.md: propose the plan
(current → target versions, what you found in the release notes) and **wait for approval** before editing.
Work on a branch, never on `main`.

## 1. Where the versions live

Only the root `pom.xml` holds versions; modules inherit and must not set their own.

| What | Where | Notes |
|---|---|---|
| Spring Boot | root `pom.xml` `<parent>` `spring-boot-starter-parent` | manages Hibernate, Flyway, Jackson, Testcontainers, Spring Security, Micrometer… |
| Spring Cloud | root `spring-cloud.version` | BOM imported **only** in `api-gateway/pom.xml`, so other modules keep Boot's versions. Keep it that way |
| Spring Modulith | root `spring-modulith.version` | BOM, used by core-app |
| springdoc | root `springdoc.version` | drives `docs/openapi.json` |
| AWS SDK | root `awssdk.version` | independent of Boot; bump separately |
| Java | root `java.version` (21), `Dockerfile` (`eclipse-temurin:21-*`), `.github/workflows/ci.yml` | change only if the new Boot requires it, and all three together |
| Maven | `.mvn/wrapper/maven-wrapper.properties` | only if the new Boot needs a newer Maven |

`springboot-template/` is git-ignored scratch and `.github/modernize/` is a local tool's folder; neither is part
of the build. Ignore them.

Confirm what is resolved now (offline, fast):

```bash
./mvnw -q -o dependency:list -DincludeArtifactIds=spring-boot,spring-cloud-gateway-server-webflux,spring-modulith-core,springdoc-openapi-starter-webmvc-api -DoutputFile=/dev/stdout | sort -u
```

## 2. Pick the target and check compatibility (before touching anything)

Latest **stable** releases from Maven Central. Don't use the metadata's `<release>` field: it is often an RC
(it showed `4.2.0-RC1` while 4.1.2 was the newest stable). The `^[0-9]` filter drops old Spring Cloud train names
like `Hoxton.SR9`, which would otherwise sort last:

```bash
for p in org/springframework/boot/spring-boot-starter-parent org/springframework/cloud/spring-cloud-dependencies \
         org/springframework/modulith/spring-modulith-bom org/springdoc/springdoc-openapi-starter-webmvc-ui; do
  printf '%-55s %s\n' "$p" "$(curl -s https://repo1.maven.org/maven2/$p/maven-metadata.xml \
    | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' | grep -E '^[0-9]' | grep -vE -- '-(M|RC|SNAPSHOT)' | sort -V | tail -1)"
done
```

Never target a milestone/RC (`-M1`, `-RC1`) unless the user asks. A newer Boot minor may already exist as an RC
while its Spring Cloud train is also only RC: that is "wait", not "upgrade". Then check, from the official sources, that the
companions support the target Boot version:

- **Spring Cloud**: release-train ↔ Boot table on https://spring.io/projects/spring-cloud and the train's release
  notes on the spring-cloud-release GitHub wiki. A Boot **minor** bump (4.1 → 4.2) usually needs a new train or a
  specific service release (e.g. Oakwood 2025.1 supports Boot 4.1 only from 2025.1.2). Boot refuses to start the
  gateway with a mismatched train, so this is the most common breakage.
- **Spring Modulith**: compatibility table on https://spring.io/projects/spring-modulith.
- **springdoc**: compatibility matrix on https://springdoc.org (FAQ).
- **Spring Boot**: release notes and, for a minor/major, the migration guide on the spring-boot GitHub wiki
  (`Spring-Boot-4.x-Release-Notes`). Read deprecations removed and property renames.

If a companion has no release for the target Boot yet, **stop and tell the user**: the options are to wait or to
upgrade Boot only to the newest version that every companion supports. Don't force it with overrides.

### What to look for in the release notes, for this repo

| Area | Why it matters here |
|---|---|
| Flyway | migrations per module schema on PostgreSQL 16 + PostGIS; a Flyway major may need `flyway-database-postgresql` changes or drop support |
| Hibernate | optimistic locking (`version` on trips), PostGIS types, `TIMESTAMPTZ` mapping |
| Spring Security / OAuth2 resource server | `platform-security` decoder auto-config (JWKS, PEM, issuer, 30 s skew, `roles` → `ROLE_*`) for servlet **and** reactive |
| Jackson | problem+json errors, money as minor units, `docs/openapi.json` shape |
| Spring AMQP, Data Redis | outbox relay to RabbitMQ; Redis GEO + hashes in location-service |
| Gateway (Cloud) | route property names (`spring.cloud.gateway.server.webflux.*`), WebSocket proxying, filters |
| Actuator | `/actuator/health` used by staging checks and compose healthchecks |
| Testcontainers | images pinned in tests (`postgis/postgis:16-3.4`, `redis:7-alpine`, `rabbitmq:3-management`, `adobe/s3mock`) |
| Removed/renamed properties | grep `application*.yml` in every module and `deploy/staging/compose.yml` env vars |

Add `spring-boot-properties-migrator` (runtime scope, core-app) temporarily only if the notes list renamed
properties; it reports them at startup. Remove it before committing (explain it to the user first, per CLAUDE.md).

## 3. Change

One upgrade per branch (`chore/spring-boot-4.2.0`). Edit only the root `pom.xml` properties/parent (plus Java,
Docker, CI files if section 1 says so). For a patch bump (4.1.1 → 4.1.2) usually only the parent changes; still
check whether the companions published matching patches.

## 4. Verify, in this order

1. `./mvnw -q -DskipTests compile` — fast signal for removed APIs.
2. `./mvnw -B -ntp dependency:tree -pl api-gateway | grep -iE 'spring-boot|cloud'` — gateway resolves the new
   Boot, not an older one from the Cloud BOM.
3. `scripts/check.sh` (full, Docker running). It runs the web-console checks and `./mvnw verify` including the
   Testcontainers ITs and `OpenApiSnapshotIT`.
4. If `OpenApiSnapshotIT` fails: the upgrade changed the generated API (often springdoc or Jackson). Run
   `./mvnw verify -Dopenapi.update=true`, then **review `git diff docs/openapi.json`**: formatting or ordering
   changes are fine; changed types, nullability or removed fields are a real API change to report to the user
   before accepting. Then run `scripts/check.sh` again (it regenerates `web-console/src/lib/api/schema.ts`).
5. `scripts/dev.sh up` then `scripts/dev.sh smoke`, then `scripts/dev.sh down`: real startup with real Redis,
   RabbitMQ, JWKS fetch and gateway routing — things unit tests don't cover.
6. Grep the startup logs (`scripts/dev.sh logs core-app`, `api-gateway`, `location-service`) for `WARN` about
   deprecated or unknown properties.
7. Run the **deploy-rehearsal** skill: new images, Flyway on a copy of the schema and rollback still work.

Report each step's result to the user as you go.

## 5. If something fails

- Compile/test failures from API changes: fix in the code, citing the release-note item. Keep the fix minimal.
- If a fix needs architectural changes or a new library, stop and ask.
- To abandon: `git switch main && git branch -D <branch>`; nothing outside the branch changed. If it already
  reached staging, follow the **staging-deploy** skill and `docs/staging.md` ("Failed deploy after a migration"):
  rolling back images does not undo a Flyway migration that already ran.

## 6. Finish

- Update the version row in the current handover (`docs/handover-gateway.md` or its successor) and the Spring
  Cloud line in CLAUDE.md if the train changed (e.g. "2025.1 Oakwood").
- Keep the comment above `spring-cloud.version` in `pom.xml` true (which train supports which Boot).
- Suggest a commit message, e.g.:

```
upgrade Spring Boot 4.1.1 -> 4.2.0

Spring Cloud 2025.1.4 -> 2026.0.0, Modulith 2.1.1 -> 2.2.0, springdoc 3.1.1 -> 3.2.0.
<one line per code change and why, from the release notes>
```

- Deploying it is a separate step: hand over to the **staging-deploy** skill.
