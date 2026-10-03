#!/bin/bash
# Runs once, when the data volume is first initialised, as the postgres superuser.
# Extensions need superuser rights, so they are created here; Flyway's V1 then finds them
# (CREATE EXTENSION IF NOT EXISTS) and the app itself never needs superuser.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE mobility_app LOGIN PASSWORD :'app_password';
-- Database owner also owns the public schema (PostgreSQL 15+), so Flyway can create schemas and tables.
ALTER DATABASE mobility OWNER TO mobility_app;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
SQL
