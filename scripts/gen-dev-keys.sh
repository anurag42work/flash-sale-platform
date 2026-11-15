#!/usr/bin/env bash
# Generate the RSA key pair auth-service uses to sign JWTs (RS256), for local development only.
# Writes keys/jwt-private.pem (PKCS#8) and keys/jwt-public.pem (X.509). keys/ is git-ignored.
# Usage: scripts/gen-dev-keys.sh [--force]
set -euo pipefail

cd "$(dirname "$0")/.."

private=keys/jwt-private.pem
public=keys/jwt-public.pem

if [ -f "$private" ] && [ "${1:-}" != "--force" ]; then
  echo "$private already exists. Use --force to replace it (all issued tokens become invalid)." >&2
  exit 1
fi

mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$private" 2>/dev/null
openssl pkey -in "$private" -pubout -out "$public"
chmod 600 "$private"

echo "Created $private and $public"
