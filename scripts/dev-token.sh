#!/usr/bin/env bash
# Tạo access token JWT (HS256) cho user seed, ký bằng JWT_SECRET local. Chỉ dùng trên máy dev.
#
#   scripts/dev-token.sh buyer      # buyer@vingo.local  (BUYER)
#   scripts/dev-token.sh buyer2     # buyer2@vingo.local (BUYER, chủ các đơn "của người khác")
#   scripts/dev-token.sh buyer3     # buyer3@vingo.local (BUYER, chưa có hồ sơ người mua)
#   scripts/dev-token.sh admin      # admin@vingo.local  (ADMIN)
#   TTL_HOURS=1 scripts/dev-token.sh buyer
#
# Claim giống AuthService: sub = user id, role, sid = id phiên seed. Secret lấy từ JWT_SECRET trong
# .env.local, không có thì dùng giá trị mặc định của application-local.yml.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="01970000-0000-7000-8000-00000000"

case "${1:-}" in
  admin)  user="${PREFIX}0001"; role=ADMIN; session="${PREFIX}0701" ;;
  buyer)  user="${PREFIX}0002"; role=BUYER; session="${PREFIX}0702" ;;
  buyer2) user="${PREFIX}0003"; role=BUYER; session="${PREFIX}0703" ;;
  buyer3) user="${PREFIX}0004"; role=BUYER; session="${PREFIX}0704" ;;
  *) echo "Dùng: $0 admin|buyer|buyer2|buyer3" >&2; exit 2 ;;
esac

secret=""
if [[ -f "$ROOT/.env.local" ]]; then
  secret="$(grep -E '^JWT_SECRET=' "$ROOT/.env.local" | tail -1 | cut -d= -f2- | sed -E 's/^["'\'']//; s/["'\'']$//' || true)"
fi
secret="${secret:-local-dev-secret-change-me-at-least-32-bytes}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
exp=$((now + ${TTL_HOURS:-12} * 3600))
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"vingo","sub":"%s","role":"%s","sid":"%s","iat":%d,"exp":%d}' \
  "$user" "$role" "$session" "$now" "$exp" | b64url)
key_hex=$(printf '%s' "$secret" | xxd -p | tr -d '\n')
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$key_hex" -binary | b64url)

echo "$header.$payload.$signature"
