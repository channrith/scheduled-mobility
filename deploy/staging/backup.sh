#!/usr/bin/env bash
# Nightly PostgreSQL backup: pg_dump (custom format, compressed) uploaded to BACKUP_S3_BUCKET under
# postgres/, then backups older than BACKUP_RETENTION_DAYS are deleted. Nothing is kept on the droplet.
#
# Cron (02:30 Phnom Penh = 19:30 UTC), as the user that runs docker compose:
#   30 19 * * * cd /opt/mobility && ./backup.sh >> /var/log/mobility-backup.log 2>&1
# Restore: see docs/staging.md.
set -euo pipefail
cd "$(dirname "$0")"

# Read only the keys needed here: .env is not valid shell (the one-line JWT keys contain spaces).
env_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2- || true; }
BACKUP_S3_ENDPOINT=$(env_value BACKUP_S3_ENDPOINT)
BACKUP_S3_REGION=$(env_value BACKUP_S3_REGION)
BACKUP_S3_BUCKET=$(env_value BACKUP_S3_BUCKET)
BACKUP_S3_ACCESS_KEY=$(env_value BACKUP_S3_ACCESS_KEY)
BACKUP_S3_SECRET_KEY=$(env_value BACKUP_S3_SECRET_KEY)
BACKUP_DOCKER_NETWORK=${BACKUP_DOCKER_NETWORK:-}
: "${BACKUP_S3_ENDPOINT:?}" "${BACKUP_S3_BUCKET:?}" "${BACKUP_S3_ACCESS_KEY:?}" "${BACKUP_S3_SECRET_KEY:?}"
retention_days=$(env_value BACKUP_RETENTION_DAYS)
retention_days=${retention_days:-14}
key="postgres/mobility-$(date -u +%Y%m%dT%H%M%SZ).dump"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

aws() {
	docker run --rm -i -v "$workdir:/work" -w /work \
		-e AWS_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" \
		-e AWS_DEFAULT_REGION="${BACKUP_S3_REGION:-us-east-1}" \
		-e AWS_REQUEST_CHECKSUM_CALCULATION=when_required -e AWS_RESPONSE_CHECKSUM_VALIDATION=when_required \
		${BACKUP_DOCKER_NETWORK:+--network "$BACKUP_DOCKER_NETWORK"} \
		amazon/aws-cli:2.31.0 --endpoint-url "$BACKUP_S3_ENDPOINT" "$@"
}

echo "$(date -u +%FT%TZ) dumping database"
docker compose exec -T postgres pg_dump -U postgres --format=custom --no-owner mobility >"$workdir/backup.dump"
[[ -s "$workdir/backup.dump" ]] || { echo "empty dump" >&2; exit 1; }

echo "$(date -u +%FT%TZ) uploading s3://$BACKUP_S3_BUCKET/$key ($(du -h "$workdir/backup.dump" | cut -f1))"
aws s3 cp --only-show-errors backup.dump "s3://$BACKUP_S3_BUCKET/$key"

cutoff=$(date -u -d "-${retention_days} days" +%FT%TZ 2>/dev/null || date -u -v-"${retention_days}"d +%FT%TZ)
old=$(aws s3api list-objects-v2 --bucket "$BACKUP_S3_BUCKET" --prefix postgres/ \
	--query "Contents[?LastModified<'$cutoff'].Key" --output text)
for old_key in $old; do
	[[ "$old_key" == "None" ]] && continue
	echo "$(date -u +%FT%TZ) deleting $old_key (older than $retention_days days)"
	aws s3 rm --only-show-errors "s3://$BACKUP_S3_BUCKET/$old_key"
done
echo "$(date -u +%FT%TZ) backup done"
