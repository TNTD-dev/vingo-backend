#!/usr/bin/env bash
# Nạp dữ liệu mẫu vào Postgres local rồi xoá cache Redis (cache search + bộ đếm rate limit).
# Cần: docker compose -f deploy/compose.dev.yaml up -d, và API đã chạy ít nhất một lần để Flyway tạo bảng.
#
#   scripts/seed-local.sh
#
# CẢNH BÁO: xoá sạch catalog, booking, payment, ticketing trên DB local trước khi nạp. Không chạy trên server.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/deploy/compose.dev.yaml")

"${COMPOSE[@]}" exec -T postgres psql -q -U vingo -d vingo -v ON_ERROR_STOP=1 < "$ROOT/scripts/seed.sql"
"${COMPOSE[@]}" exec -T redis redis-cli FLUSHDB > /dev/null
echo "Đã xoá cache Redis."

echo
echo "== Access token (hạn 12 giờ), dán vào nút Authorize trên Swagger =="
for who in buyer buyer2 admin; do
  printf '%-7s %s\n' "$who" "$("$ROOT/scripts/dev-token.sh" "$who")"
done
echo
echo "Refresh token seed (dùng 1 lần, chạy lại seed để có lại): seed-refresh-buyer, seed-refresh-buyer2,"
echo "seed-refresh-admin; lỗi 401: seed-refresh-revoked, seed-refresh-expired, seed-refresh-locked."
