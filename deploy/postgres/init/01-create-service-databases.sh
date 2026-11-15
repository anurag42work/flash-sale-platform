#!/usr/bin/env bash
# Runs once, on the first start of an empty Postgres volume.
# One login role per service, owning one database. Other roles cannot connect to it,
# so a service can only see its own data (no cross-service joins).
set -euo pipefail

create_service_db() {
  local service=$1 password=$2
  if [ -z "$password" ]; then
    echo "Missing password for ${service}_user (set ${service^^}_DB_PASSWORD in .env)" >&2
    exit 1
  fi
  # Password passed as a psql variable (:'pw'), so it is quoted safely.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pw="$password" <<SQL
CREATE ROLE ${service}_user LOGIN PASSWORD :'pw';
CREATE DATABASE ${service}_db OWNER ${service}_user;
REVOKE ALL ON DATABASE ${service}_db FROM PUBLIC;
SQL
}

create_service_db auth    "${AUTH_DB_PASSWORD:-}"
create_service_db catalog "${CATALOG_DB_PASSWORD:-}"
create_service_db order   "${ORDER_DB_PASSWORD:-}"
create_service_db payment "${PAYMENT_DB_PASSWORD:-}"
