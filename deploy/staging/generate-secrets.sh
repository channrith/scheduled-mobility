#!/usr/bin/env bash
# Fills every empty secret in .env (created from .env.staging.example if missing). Values that are
# already set are never changed: rotating PII_ENCRYPTION_KEY or the database passwords would make
# existing data unreadable or lock the apps out.
#
# Optional: PUBLIC_IP=203.0.113.7 ./generate-secrets.sh also fills API_HOST/LOCATION_HOST with
# sslip.io names. Without it, the droplet's IP is read from the DigitalOcean metadata service.
set -euo pipefail
cd "$(dirname "$0")"
umask 077

[[ -f .env ]] || cp .env.staging.example .env
chmod 600 .env

current() { grep -E "^$1=" .env | head -1 | cut -d= -f2- || true; }

set_if_empty() {
	local key=$1 value=$2
	if [[ -n "$(current "$key")" ]]; then
		echo "  keep  $key"
		return
	fi
	local tmp
	tmp=$(mktemp .env.XXXXXX)
	awk -v k="$key" -v v="$value" 'BEGIN { done = 0 }
		index($0, k "=") == 1 && !done { print k "=" v; done = 1; next }
		{ print }
		END { if (!done) print k "=" v }' .env >"$tmp"
	mv "$tmp" .env
	echo "  set   $key"
}

password() { openssl rand -hex 24; }
aes_key() { openssl rand -base64 32; }
hmac_secret() { openssl rand -hex 32; }
one_line() { tr -d '\n' <"$1"; }

echo "Secrets:"
set_if_empty POSTGRES_SUPERUSER_PASSWORD "$(password)"
set_if_empty APP_DB_PASSWORD "$(password)"
set_if_empty REDIS_PASSWORD "$(password)"
set_if_empty RABBITMQ_PASSWORD "$(password)"
set_if_empty OTP_HMAC_SECRET "$(hmac_secret)"
set_if_empty IDEMPOTENCY_ENCRYPTION_KEY "$(aes_key)"
set_if_empty PII_ENCRYPTION_KEY "$(aes_key)"
set_if_empty PII_HASH_SECRET "$(hmac_secret)"

if [[ -z "$(current JWT_PRIVATE_KEY)" ]]; then
	keys=$(mktemp -d)
	trap 'rm -rf "$keys"' EXIT
	openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$keys/private.pem" 2>/dev/null
	openssl pkey -in "$keys/private.pem" -pubout -out "$keys/public.pem"
	set_if_empty JWT_PRIVATE_KEY "$(one_line "$keys/private.pem")"
	# The public key must match the private key, so it is replaced together with it.
	sed -i.bak '/^JWT_PUBLIC_KEY=/d' .env && rm -f .env.bak
	set_if_empty JWT_PUBLIC_KEY "$(one_line "$keys/public.pem")"
else
	echo "  keep  JWT_PRIVATE_KEY / JWT_PUBLIC_KEY"
fi

ip=${PUBLIC_IP:-$(curl -fsS --max-time 2 http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address 2>/dev/null || true)}
if [[ -n "$ip" ]]; then
	echo "Hosts (sslip.io for $ip):"
	set_if_empty API_HOST "api.${ip//./-}.sslip.io"
	set_if_empty LOCATION_HOST "loc.${ip//./-}.sslip.io"
fi

missing=()
for key in API_HOST LOCATION_HOST ACME_EMAIL IMAGE_TAG BOOTSTRAP_ADMIN_PHONE STORAGE_S3_BUCKET \
	STORAGE_S3_ACCESS_KEY STORAGE_S3_SECRET_KEY BACKUP_S3_BUCKET BACKUP_S3_ACCESS_KEY BACKUP_S3_SECRET_KEY; do
	[[ -n "$(current "$key")" ]] || missing+=("$key")
done
if ((${#missing[@]})); then
	echo "Still to fill in by hand in .env: ${missing[*]}"
fi
