#!/usr/bin/env bash
# Build and run ONE service on the host with a small JVM, against the local infra.
# Usage: scripts/run-local.sh <auth|catalog|order|payment|gateway>[-service]
# Run at most 1-2 services at a time on the 8 GB laptop.
set -euo pipefail

cd "$(dirname "$0")/.."

svc=${1:?usage: scripts/run-local.sh <auth|catalog|order|payment|gateway>}
svc=${svc%-service}
module="${svc}-service"

if [ ! -d "$module" ]; then
  echo "Unknown service: $svc" >&2
  exit 1
fi

if [ ! -f .env ]; then
  echo "Missing .env. Run: cp .env.example .env (then set passwords)" >&2
  exit 1
fi

# Export everything in .env to this process.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

# Map the service's own DB password to the generic name application.yml reads.
case "$svc" in
  auth)    export DB_PASSWORD=$AUTH_DB_PASSWORD ;;
  catalog) export DB_PASSWORD=$CATALOG_DB_PASSWORD ;;
  order)   export DB_PASSWORD=$ORDER_DB_PASSWORD ;;
  payment) export DB_PASSWORD=$PAYMENT_DB_PASSWORD ;;
  gateway) ;; # no database
esac

if [ "$svc" = auth ] && [ -z "${JWT_PRIVATE_KEY:-}" ] && [ ! -f keys/jwt-private.pem ]; then
  echo "Missing JWT signing keys. Run: scripts/gen-dev-keys.sh" >&2
  exit 1
fi

echo "Building $module ..."
./mvnw -q -pl "$module" -am -DskipTests package

jar=$(ls "$module"/target/"$module"-*.jar | grep -v '\.original$' | head -n 1)

# Small, fast-starting JVM for local dev (not for production or load tests):
# 256 MB heap, serial GC (least memory overhead), C1 JIT only, smaller thread stacks.
JAVA_OPTS=${JAVA_OPTS:-"-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"}

echo "Starting $module: java $JAVA_OPTS -jar $jar"
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$jar"
