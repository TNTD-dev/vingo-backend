#!/usr/bin/env bash
# Mint a fresh local session for a seeded account. Only stdout contains the credential.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
case "${1:-}" in
  admin|buyer|buyer2|buyer3) email="$1@vingo.local" ;;
  *) echo "Usage: $0 admin|buyer|buyer2|buyer3" >&2; exit 2 ;;
esac
ttl="${AUTH_SESSION_TTL:-P7D}"
token="$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
hash="$(printf '%s' "$token" | openssl dgst -sha256 -r | cut -d' ' -f1)"
docker compose -f "$root/deploy/compose.dev.yaml" exec -T postgres psql -Xq -U vingo -d vingo \
  -v ON_ERROR_STOP=1 -v email="$email" -v hash="$hash" -v ttl="$ttl" <<'SQL'
INSERT INTO login_session (user_id, token_hash, expires_at, user_agent)
VALUES ((SELECT id FROM user_account WHERE email = :'email' AND deleted_at IS NULL
         AND email_verified_at IS NOT NULL AND NOT admin_locked),
        :'hash', now() + :'ttl'::interval, 'dev-token.sh');
SQL
printf '%s\n' "$token"
