-- Dữ liệu mẫu cho máy dev. Chạy sau khi API đã khởi động (Flyway đã tạo bảng):
--   docker compose -f deploy/compose.dev.yaml exec -T postgres psql -U vingo -d vingo < scripts/seed.sql
-- Chạy lại nhiều lần được (bỏ qua nếu đã có).
--
-- Tài khoản dev (chỉ dùng local):
--   admin@vingo.local / Vingo@2026  (ADMIN)
--   buyer@vingo.local / Vingo@2026  (BUYER)

BEGIN;

INSERT INTO users (id, email, phone, full_name, password_hash, role, status) VALUES
    ('01970000-0000-7000-8000-000000000001', 'admin@vingo.local', '+84900000001', 'Admin Vingo',
     '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'ADMIN', 'ACTIVE'),
    ('01970000-0000-7000-8000-000000000002', 'buyer@vingo.local', '+84900000002', 'Nguyễn Văn Mua',
     '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO buyer_profile (user_id, full_name, phone, email) VALUES
    ('01970000-0000-7000-8000-000000000002', 'Nguyễn Văn Mua', '+84900000002', 'buyer@vingo.local')
ON CONFLICT DO NOTHING;

-- Sân Mỹ Đình, 1 khán đài, sơ đồ đã PUBLISHED: khu A ngồi (3 hàng x 10 ghế, hàng C ghế đôi), khu đứng 200 chỗ.
INSERT INTO venue (id, name, type, address, city) VALUES
    ('01970000-0000-7000-8000-000000000101', 'Sân vận động Mỹ Đình', 'STADIUM', 'Lê Đức Thọ, Nam Từ Liêm', 'Hà Nội')
ON CONFLICT DO NOTHING;
INSERT INTO hall (id, venue_id, name) VALUES
    ('01970000-0000-7000-8000-000000000111', '01970000-0000-7000-8000-000000000101', 'Khán đài A')
ON CONFLICT DO NOTHING;
INSERT INTO seat_layout (id, hall_id, status, published_at) VALUES
    ('01970000-0000-7000-8000-000000000121', '01970000-0000-7000-8000-000000000111', 'PUBLISHED', now())
ON CONFLICT DO NOTHING;
INSERT INTO zone (id, seat_layout_id, name, kind, capacity) VALUES
    ('01970000-0000-7000-8000-000000000131', '01970000-0000-7000-8000-000000000121', 'A', 'SEATED', 30),
    ('01970000-0000-7000-8000-000000000132', '01970000-0000-7000-8000-000000000121', 'Khu đứng', 'STANDING', 200)
ON CONFLICT DO NOTHING;

INSERT INTO seat (zone_id, seat_type_id, row_label, number, x, y)
SELECT '01970000-0000-7000-8000-000000000131',
       (SELECT id FROM seat_type WHERE code = CASE WHEN r = 'C' THEN 'COUPLE' ELSE 'STANDARD' END),
       r, n, n * 40, (ascii(r) - ascii('A')) * 50
FROM unnest(ARRAY['A', 'B', 'C']) AS r, generate_series(1, 10) AS n
ON CONFLICT DO NOTHING;

-- Ghép đôi hàng C: (1,2), (3,4), ...
INSERT INTO seat_pair (seat_a_id, seat_b_id)
SELECT least(a.id, b.id), greatest(a.id, b.id)
FROM seat a JOIN seat b ON a.zone_id = b.zone_id AND a.row_label = b.row_label AND b.number = a.number + 1
WHERE a.zone_id = '01970000-0000-7000-8000-000000000131' AND a.row_label = 'C' AND a.number % 2 = 1
ON CONFLICT DO NOTHING;

-- Sự kiện đã công bố, 1 suất đang mở bán.
INSERT INTO event (id, title, description, category, status, max_tickets_per_order, published_at) VALUES
    ('01970000-0000-7000-8000-000000000201', 'Việt Nam vs Thái Lan', 'Vòng loại, sân Mỹ Đình', 'FOOTBALL', 'PUBLISHED', 6, now())
ON CONFLICT DO NOTHING;
INSERT INTO showtime (id, event_id, hall_id, seat_layout_id, starts_at, ends_at, sale_opens_at, sale_closes_at, status) VALUES
    ('01970000-0000-7000-8000-000000000211', '01970000-0000-7000-8000-000000000201',
     '01970000-0000-7000-8000-000000000111', '01970000-0000-7000-8000-000000000121',
     date_trunc('day', now()) + interval '30 days 12 hours', date_trunc('day', now()) + interval '30 days 14 hours',
     now() - interval '1 day', date_trunc('day', now()) + interval '30 days 11 hours', 'SCHEDULED')
ON CONFLICT DO NOTHING;

INSERT INTO price_tier (id, event_id, name, price, color) VALUES
    ('01970000-0000-7000-8000-000000000301', '01970000-0000-7000-8000-000000000201', 'Hạng A', 500000, '#E4572E'),
    ('01970000-0000-7000-8000-000000000302', '01970000-0000-7000-8000-000000000201', 'Đứng', 200000, '#4C956C')
ON CONFLICT DO NOTHING;
INSERT INTO zone_price (event_id, zone_id, price_tier_id) VALUES
    ('01970000-0000-7000-8000-000000000201', '01970000-0000-7000-8000-000000000131', '01970000-0000-7000-8000-000000000301'),
    ('01970000-0000-7000-8000-000000000201', '01970000-0000-7000-8000-000000000132', '01970000-0000-7000-8000-000000000302')
ON CONFLICT DO NOTHING;

-- Tồn kho của suất.
INSERT INTO showtime_seat (showtime_id, seat_id)
SELECT '01970000-0000-7000-8000-000000000211', id FROM seat WHERE zone_id = '01970000-0000-7000-8000-000000000131'
ON CONFLICT DO NOTHING;
INSERT INTO showtime_zone_stock (showtime_id, zone_id, capacity) VALUES
    ('01970000-0000-7000-8000-000000000211', '01970000-0000-7000-8000-000000000132', 200)
ON CONFLICT DO NOTHING;

COMMIT;
