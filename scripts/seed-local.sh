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
echo "Tạo token phiên dùng với Swagger bằng scripts/dev-token.sh buyer (hoặc admin)."
