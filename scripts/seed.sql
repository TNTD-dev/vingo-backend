-- Dữ liệu mẫu cho máy dev, đủ để gọi thử mọi API đã có (search, booking, auth refresh/logout) kể cả các nhánh lỗi.
-- Chạy qua scripts/seed-local.sh (nạp SQL rồi xoá cache Redis). Chỉ dùng cho DB local.
--
-- CẢNH BÁO: script XOÁ SẠCH dữ liệu catalog, booking, payment, ticketing rồi nạp lại từ đầu.
-- Bảng users giữ nguyên, chỉ ghi đè các user seed và xoá phiên/idempotency của họ.
-- Chạy lại nhiều lần được; mọi mốc giờ tính từ now() nên mỗi lần chạy lại trạng thái "đang mở bán" vẫn đúng.
--
-- Id cố định dạng 01970000-0000-7000-8000-00000000NNNN, xem pg_temp.sid(NNNN).
-- Danh sách kịch bản test và id dùng cho Swagger: xem phần cuối file hoặc output của seed-local.sh.

\set ON_ERROR_STOP on
-- Ẩn kết quả rỗng của các lệnh SELECT pg_temp.*(...) bên dưới; bật lại ở phần tóm tắt cuối file.
\o /dev/null
BEGIN;

-- ------------------------------------------------------------------ Hàm phụ (chỉ sống trong phiên psql này)

CREATE FUNCTION pg_temp.sid(n int) RETURNS uuid LANGUAGE sql IMMUTABLE AS
$$ SELECT ('01970000-0000-7000-8000-' || lpad(n::text, 12, '0'))::uuid $$;

-- Tạo ghế cho khu ngồi: mỗi hàng per_row ghế. Hàng trong couple_rows là ghế đôi, ghép (1,2), (3,4)...
CREATE FUNCTION pg_temp.add_seats(z uuid, rows_ text[], per_row int,
                                  couple_rows text[] DEFAULT '{}', wheelchair_rows text[] DEFAULT '{}')
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO seat (zone_id, seat_type_id, row_label, number, x, y)
    SELECT z,
           (SELECT id FROM seat_type WHERE code = CASE WHEN r = ANY (couple_rows) THEN 'COUPLE'
                                                       WHEN r = ANY (wheelchair_rows) THEN 'WHEELCHAIR'
                                                       ELSE 'STANDARD' END),
           r, n, n * 40, (array_position(rows_, r) - 1) * 50
    FROM unnest(rows_) AS r, generate_series(1, per_row) AS n;

    INSERT INTO seat_pair (seat_a_id, seat_b_id)
    SELECT least(a.id, b.id), greatest(a.id, b.id)
    FROM seat a JOIN seat b ON b.zone_id = a.zone_id AND b.row_label = a.row_label AND b.number = a.number + 1
    WHERE a.zone_id = z AND a.row_label = ANY (couple_rows) AND a.number % 2 = 1;
END $$;

CREATE FUNCTION pg_temp.seat_of(z uuid, r text, n int) RETURNS uuid LANGUAGE sql AS
$$ SELECT id FROM seat WHERE zone_id = z AND row_label = r AND number = n $$;

-- Suất diễn dài 2 giờ. sale_closes mặc định = lúc bắt đầu.
CREATE FUNCTION pg_temp.add_showtime(id_ uuid, ev uuid, layout uuid, starts timestamptz,
                                     sale_opens timestamptz, sale_closes timestamptz DEFAULT NULL,
                                     st text DEFAULT 'SCHEDULED')
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO showtime (id, event_id, hall_id, seat_layout_id, starts_at, ends_at, sale_opens_at, sale_closes_at, status)
    SELECT id_, ev, l.hall_id, layout, starts, starts + interval '2 hours', sale_opens, coalesce(sale_closes, starts), st
    FROM seat_layout l WHERE l.id = layout;

    -- Tồn kho: mỗi ghế một dòng AVAILABLE, mỗi khu đứng một dòng bộ đếm.
    INSERT INTO showtime_seat (showtime_id, seat_id)
    SELECT id_, s.id FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = layout;
    INSERT INTO showtime_zone_stock (showtime_id, zone_id, capacity)
    SELECT id_, z.id, z.capacity FROM zone z WHERE z.seat_layout_id = layout AND z.kind = 'STANDING';
END $$;

CREATE FUNCTION pg_temp.add_order(id_ uuid, code_ text, usr uuid, st uuid, status_ text,
                                  expires timestamptz, review text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO orders (id, code, user_id, showtime_id, status, total_amount, expires_at,
                        buyer_name, buyer_email, buyer_phone, paid_at, review_reason, created_at)
    SELECT id_, code_, usr, st, status_, 0, expires, u.full_name, u.email, u.phone,
           CASE WHEN status_ IN ('PAID', 'NEEDS_REVIEW') THEN expires - interval '2 minutes' END,
           review, expires - interval '10 minutes'
    FROM users u WHERE u.id = usr;

    INSERT INTO order_status_history (order_id, from_status, to_status, actor_id, at)
    VALUES (id_, NULL, 'PENDING', usr, expires - interval '10 minutes');
    IF status_ <> 'PENDING' THEN
        INSERT INTO order_status_history (order_id, from_status, to_status, actor_id, reason, at)
        VALUES (id_, 'PENDING', status_, CASE WHEN status_ = 'CANCELLED' THEN usr END, review,
                expires - interval '2 minutes');
    END IF;
END $$;

-- Gắn ghế (hàng r, số từ a đến b) vào đơn: thêm order_item và đổi trạng thái showtime_seat.
-- seat_status NULL = chỉ ghi order_item, ghế giữ nguyên (đơn đã huỷ/hết hạn đã nhả ghế).
CREATE FUNCTION pg_temp.order_seats(ord uuid, z uuid, r text, a int, b int,
                                    seat_status text, held_until_ timestamptz DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE st uuid; ev uuid;
BEGIN
    SELECT o.showtime_id, s.event_id INTO st, ev FROM orders o JOIN showtime s ON s.id = o.showtime_id WHERE o.id = ord;

    INSERT INTO order_item (order_id, zone_id, seat_id, price_tier_id, quantity, unit_price)
    SELECT ord, z, se.id, pt.id, 1, pt.price
    FROM seat se JOIN zone_price zp ON zp.event_id = ev AND zp.zone_id = z JOIN price_tier pt ON pt.id = zp.price_tier_id
    WHERE se.zone_id = z AND se.row_label = r AND se.number BETWEEN a AND b;

    IF seat_status IS NOT NULL THEN
        UPDATE showtime_seat ss SET status = seat_status, order_id = ord, held_until = held_until_
        FROM seat se
        WHERE ss.showtime_id = st AND ss.seat_id = se.id AND se.zone_id = z AND se.row_label = r
          AND se.number BETWEEN a AND b;
    END IF;
    UPDATE orders SET total_amount = (SELECT coalesce(sum(unit_price * quantity), 0) FROM order_item WHERE order_id = ord)
    WHERE id = ord;
END $$;

-- Khu đứng: thêm order_item và cộng bộ đếm held/sold (stock_col NULL = không đụng bộ đếm).
CREATE FUNCTION pg_temp.order_standing(ord uuid, z uuid, qty int, stock_col text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE st uuid; ev uuid;
BEGIN
    SELECT o.showtime_id, s.event_id INTO st, ev FROM orders o JOIN showtime s ON s.id = o.showtime_id WHERE o.id = ord;

    INSERT INTO order_item (order_id, zone_id, seat_id, price_tier_id, quantity, unit_price)
    SELECT ord, z, NULL, pt.id, qty, pt.price
    FROM zone_price zp JOIN price_tier pt ON pt.id = zp.price_tier_id WHERE zp.event_id = ev AND zp.zone_id = z;

    IF stock_col = 'sold' THEN
        UPDATE showtime_zone_stock SET sold = sold + qty WHERE showtime_id = st AND zone_id = z;
    ELSIF stock_col = 'held' THEN
        UPDATE showtime_zone_stock SET held = held + qty WHERE showtime_id = st AND zone_id = z;
    END IF;
    UPDATE orders SET total_amount = (SELECT coalesce(sum(unit_price * quantity), 0) FROM order_item WHERE order_id = ord)
    WHERE id = ord;
END $$;

-- ------------------------------------------------------------------ Xoá dữ liệu cũ

TRUNCATE checkin, ticket, payment_transaction, payment_intent, order_status_history, order_item,
         showtime_seat, showtime_zone_stock, orders, zone_price, price_tier, showtime, event,
         seat_pair, seat, zone, seat_layout, hall, venue CASCADE;

DELETE FROM sessions        WHERE user_id IN (SELECT pg_temp.sid(n) FROM generate_series(1, 5) n);
DELETE FROM idempotency_key WHERE user_id IN (SELECT pg_temp.sid(n) FROM generate_series(1, 5) n);
DELETE FROM login_attempt   WHERE user_id IN (SELECT pg_temp.sid(n) FROM generate_series(1, 5) n);

-- ------------------------------------------------------------------ Users và phiên
-- Mật khẩu Vingo@2026 (Argon2) để dành cho API login khi làm xong; hiện lấy token bằng refresh hoặc dev-token.sh.

INSERT INTO users (id, email, phone, full_name, password_hash, role, status) VALUES
    (pg_temp.sid(1), 'admin@vingo.local',  '+84900000001', 'Admin Vingo',     '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'ADMIN', 'ACTIVE'),
    (pg_temp.sid(2), 'buyer@vingo.local',  '+84900000002', 'Nguyễn Văn Mua',  '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', 'ACTIVE'),
    (pg_temp.sid(3), 'buyer2@vingo.local', '+84900000003', 'Trần Thị Khác',   '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', 'ACTIVE'),
    (pg_temp.sid(4), 'buyer3@vingo.local', '+84900000004', 'Lê Văn Chưa Hồ Sơ', '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', 'ACTIVE'),
    (pg_temp.sid(5), 'locked@vingo.local', '+84900000005', 'Phạm Bị Khoá',    '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', 'LOCKED')
ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, phone = EXCLUDED.phone, full_name = EXCLUDED.full_name,
    password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, status = EXCLUDED.status, locked_until = NULL;

INSERT INTO buyer_profile (user_id, full_name, phone, email) VALUES
    (pg_temp.sid(2), 'Nguyễn Văn Mua', '+84900000002', 'buyer@vingo.local'),
    (pg_temp.sid(3), 'Trần Thị Khác',  '+84900000003', 'buyer2@vingo.local')
ON CONFLICT (user_id) DO UPDATE SET full_name = EXCLUDED.full_name, phone = EXCLUDED.phone, email = EXCLUDED.email;
DELETE FROM buyer_profile WHERE user_id = pg_temp.sid(4);

-- Refresh token dạng chữ cố định, DB chỉ lưu SHA-256 hex như TokenSecrets.hash().
-- Refresh xoay vòng: dùng 1 lần là token cũ hết hiệu lực, muốn dùng lại thì chạy lại seed.
INSERT INTO sessions (id, user_id, refresh_token_hash, device, expires_at, revoked_at)
SELECT pg_temp.sid(sid_n), pg_temp.sid(user_n), encode(sha256(convert_to(token, 'UTF8')), 'hex'), 'seed', exp, rev
FROM (VALUES
    (701, 1, 'seed-refresh-admin',   now() + interval '30 days', NULL::timestamptz),
    (702, 2, 'seed-refresh-buyer',   now() + interval '30 days', NULL),
    (703, 3, 'seed-refresh-buyer2',  now() + interval '30 days', NULL),
    (704, 4, 'seed-refresh-buyer3',  now() + interval '30 days', NULL),
    (705, 2, 'seed-refresh-revoked', now() + interval '30 days', now() - interval '1 hour'),
    (706, 2, 'seed-refresh-expired', now() - interval '1 hour',  NULL),
    (707, 5, 'seed-refresh-locked',  now() + interval '30 days', NULL)
) AS t (sid_n, user_n, token, exp, rev);

-- ------------------------------------------------------------------ Sân, khán đài, sơ đồ ghế

INSERT INTO venue (id, name, type, address, city) VALUES
    (pg_temp.sid(101), 'Sân vận động Mỹ Đình',   'STADIUM', 'Lê Đức Thọ, Nam Từ Liêm',  'HN'),
    (pg_temp.sid(102), 'Nhà hát Lớn Hà Nội',     'THEATER', '1 Tràng Tiền, Hoàn Kiếm', 'HN'),
    (pg_temp.sid(103), 'Sân vận động Quân khu 7', 'STADIUM', '202 Hoàng Văn Thụ, Tân Bình', 'HCM'),
    (pg_temp.sid(104), 'Nhà hát Hòa Bình',       'THEATER', '240 Ba Tháng Hai, Quận 10', 'HCM'),
    (pg_temp.sid(105), 'Công viên Biển Đông',    'OUTDOOR', 'Võ Nguyên Giáp, Sơn Trà',   'DN');

INSERT INTO hall (id, venue_id, name) VALUES
    (pg_temp.sid(111), pg_temp.sid(101), 'Khán đài A'),
    (pg_temp.sid(112), pg_temp.sid(102), 'Khán phòng chính'),
    (pg_temp.sid(113), pg_temp.sid(103), 'Sân chính'),
    (pg_temp.sid(114), pg_temp.sid(104), 'Khán phòng 1'),
    (pg_temp.sid(115), pg_temp.sid(105), 'Sân khấu ngoài trời');

INSERT INTO seat_layout (id, hall_id, status, published_at)
SELECT pg_temp.sid(120 + n), pg_temp.sid(110 + n), 'PUBLISHED', now() - interval '60 days'
FROM generate_series(1, 5) n;

INSERT INTO zone (id, seat_layout_id, name, kind, capacity) VALUES
    -- L1 Mỹ Đình: A (hàng C ghế đôi), B (hàng E có 10 chỗ xe lăn), khu đứng, VIP Box
    (pg_temp.sid(131), pg_temp.sid(121), 'A',          'SEATED',   30),
    (pg_temp.sid(132), pg_temp.sid(121), 'B',          'SEATED',   20),
    (pg_temp.sid(133), pg_temp.sid(121), 'Khu đứng',   'STANDING', 200),
    (pg_temp.sid(134), pg_temp.sid(121), 'VIP Box',    'SEATED',   4),
    -- L2 Nhà hát Lớn
    (pg_temp.sid(141), pg_temp.sid(122), 'Tầng 1',     'SEATED',   60),
    (pg_temp.sid(142), pg_temp.sid(122), 'Tầng 2',     'SEATED',   24),
    -- L3 Quân khu 7
    (pg_temp.sid(151), pg_temp.sid(123), 'Khán đài A', 'SEATED',   20),
    (pg_temp.sid(152), pg_temp.sid(123), 'Sân cỏ',     'STANDING', 500),
    -- L4 Hòa Bình (hàng D ghế đôi)
    (pg_temp.sid(161), pg_temp.sid(124), 'Sảnh chính', 'SEATED',   40),
    -- L5 Biển Đông: GA chỉ 5 chỗ để test hết chỗ
    (pg_temp.sid(171), pg_temp.sid(125), 'GA',         'STANDING', 5),
    (pg_temp.sid(172), pg_temp.sid(125), 'Fanzone',    'STANDING', 1000);

SELECT pg_temp.add_seats(pg_temp.sid(131), ARRAY['A', 'B', 'C'], 10, couple_rows => ARRAY['C']);
SELECT pg_temp.add_seats(pg_temp.sid(132), ARRAY['D', 'E'], 10, wheelchair_rows => ARRAY['E']);
SELECT pg_temp.add_seats(pg_temp.sid(134), ARRAY['V'], 4);
SELECT pg_temp.add_seats(pg_temp.sid(141), ARRAY['A', 'B', 'C', 'D', 'E'], 12);
SELECT pg_temp.add_seats(pg_temp.sid(142), ARRAY['F', 'G'], 12);
SELECT pg_temp.add_seats(pg_temp.sid(151), ARRAY['A', 'B'], 10);
SELECT pg_temp.add_seats(pg_temp.sid(161), ARRAY['A', 'B', 'C', 'D'], 10, couple_rows => ARRAY['D']);

-- ------------------------------------------------------------------ Sự kiện, giá

INSERT INTO event (id, title, description, category, attributes, poster_url, status, max_tickets_per_order, published_at) VALUES
    (pg_temp.sid(201), 'Việt Nam vs Thái Lan', 'Vòng loại Asian Cup, sân Mỹ Đình', 'FOOTBALL',
     '{"home_team": "Việt Nam", "away_team": "Thái Lan", "competition": "Vòng loại Asian Cup"}',
     'https://picsum.photos/seed/vingo201/600/800', 'PUBLISHED', 6, now() - interval '20 days'),
    (pg_temp.sid(202), 'Sơn Tùng M-TP Sky Tour 2026', 'Tour diễn xuyên Việt của Sơn Tùng M-TP', 'CONCERT',
     '{"artists": ["Sơn Tùng M-TP"], "genre": "V-Pop"}',
     'https://picsum.photos/seed/vingo202/600/800', 'PUBLISHED', 4, now() - interval '15 days'),
    (pg_temp.sid(203), 'Kịch: Lôi Vũ', 'Vở kịch kinh điển của Tào Ngu', 'THEATER',
     '{"director": "NSND Lê Hùng", "duration_minutes": 150}',
     'https://picsum.photos/seed/vingo203/600/800', 'PUBLISHED', 8, now() - interval '30 days'),
    (pg_temp.sid(204), 'Hòa nhạc Giao hưởng Mùa Thu', 'Dàn nhạc Giao hưởng TP.HCM', 'CONCERT',
     '{"artists": ["HBSO"], "genre": "Cổ điển"}',
     'https://picsum.photos/seed/vingo204/600/800', 'PUBLISHED', 10, now() - interval '40 days'),
    (pg_temp.sid(205), 'Đêm nhạc Trịnh Công Sơn', 'Đêm nhạc ngoài trời bên biển', 'CONCERT',
     '{"artists": ["Hồng Nhung", "Quang Dũng"], "genre": "Nhạc Trịnh"}',
     'https://picsum.photos/seed/vingo205/600/800', 'PUBLISHED', 10, now() - interval '10 days'),
    (pg_temp.sid(206), 'Workshop làm gốm Bát Tràng', 'Tự tay nặn gốm cùng nghệ nhân', 'OTHER',
     '{"level": "Người mới"}', NULL, 'PUBLISHED', 2, now() - interval '10 days'),
    (pg_temp.sid(207), 'Sự kiện nháp chưa công bố', 'Không được hiện ở search', 'FOOTBALL',
     '{}', NULL, 'DRAFT', 10, NULL),
    (pg_temp.sid(208), 'Đêm nhạc đã huỷ', 'Sự kiện đã huỷ, không hiện ở search', 'CONCERT',
     '{}', NULL, 'CANCELLED', 10, now() - interval '50 days'),
    (pg_temp.sid(209), 'Hà Nội FC vs Hải Phòng FC', 'V-League vòng 8', 'FOOTBALL',
     '{"home_team": "Hà Nội FC", "away_team": "Hải Phòng FC", "competition": "V-League"}',
     'https://picsum.photos/seed/vingo209/600/800', 'PUBLISHED', 10, now() - interval '5 days'),
    (pg_temp.sid(210), 'SHB Đà Nẵng vs Hoàng Anh Gia Lai', 'V-League vòng 8', 'FOOTBALL',
     '{"home_team": "SHB Đà Nẵng", "away_team": "Hoàng Anh Gia Lai", "competition": "V-League"}',
     'https://picsum.photos/seed/vingo210/600/800', 'PUBLISHED', 10, now() - interval '5 days');

INSERT INTO price_tier (id, event_id, name, price, color) VALUES
    (pg_temp.sid(301), pg_temp.sid(201), 'Hạng A',    500000, '#E4572E'),
    (pg_temp.sid(302), pg_temp.sid(201), 'Hạng B',    350000, '#F3A712'),
    (pg_temp.sid(303), pg_temp.sid(201), 'Đứng',      200000, '#4C956C'),
    (pg_temp.sid(311), pg_temp.sid(202), 'VIP',      3000000, '#8E44AD'),
    (pg_temp.sid(312), pg_temp.sid(202), 'GA',       1200000, '#2E86AB'),
    (pg_temp.sid(321), pg_temp.sid(203), 'Tầng 1',    450000, '#C0392B'),
    (pg_temp.sid(322), pg_temp.sid(203), 'Tầng 2',    300000, '#16A085'),
    (pg_temp.sid(331), pg_temp.sid(204), 'Thường',    600000, '#34495E'),
    (pg_temp.sid(341), pg_temp.sid(205), 'GA',        250000, '#D35400'),
    (pg_temp.sid(342), pg_temp.sid(205), 'Fanzone',   150000, '#27AE60'),
    (pg_temp.sid(351), pg_temp.sid(206), 'Vé lớp',    400000, '#7F8C8D'),
    (pg_temp.sid(355), pg_temp.sid(207), 'Thường',    100000, NULL),
    (pg_temp.sid(361), pg_temp.sid(209), 'Hạng A',    250000, '#E4572E'),
    (pg_temp.sid(362), pg_temp.sid(209), 'Hạng B',    150000, '#F3A712'),
    (pg_temp.sid(363), pg_temp.sid(209), 'Đứng',       80000, '#4C956C'),
    (pg_temp.sid(364), pg_temp.sid(209), 'VIP Box',  1000000, '#8E44AD'),
    (pg_temp.sid(371), pg_temp.sid(210), 'GA',        200000, '#D35400'),
    (pg_temp.sid(372), pg_temp.sid(210), 'Fanzone',   100000, '#27AE60');

-- E1 cố ý không gán giá cho VIP Box (134) để test "suất chưa có giá cho khu này".
INSERT INTO zone_price (event_id, zone_id, price_tier_id) VALUES
    (pg_temp.sid(201), pg_temp.sid(131), pg_temp.sid(301)),
    (pg_temp.sid(201), pg_temp.sid(132), pg_temp.sid(302)),
    (pg_temp.sid(201), pg_temp.sid(133), pg_temp.sid(303)),
    (pg_temp.sid(202), pg_temp.sid(151), pg_temp.sid(311)),
    (pg_temp.sid(202), pg_temp.sid(152), pg_temp.sid(312)),
    (pg_temp.sid(202), pg_temp.sid(171), pg_temp.sid(311)),
    (pg_temp.sid(202), pg_temp.sid(172), pg_temp.sid(312)),
    (pg_temp.sid(203), pg_temp.sid(141), pg_temp.sid(321)),
    (pg_temp.sid(203), pg_temp.sid(142), pg_temp.sid(322)),
    (pg_temp.sid(204), pg_temp.sid(161), pg_temp.sid(331)),
    (pg_temp.sid(205), pg_temp.sid(171), pg_temp.sid(341)),
    (pg_temp.sid(205), pg_temp.sid(172), pg_temp.sid(342)),
    (pg_temp.sid(206), pg_temp.sid(161), pg_temp.sid(351)),
    (pg_temp.sid(207), pg_temp.sid(131), pg_temp.sid(355)),
    (pg_temp.sid(209), pg_temp.sid(131), pg_temp.sid(361)),
    (pg_temp.sid(209), pg_temp.sid(132), pg_temp.sid(362)),
    (pg_temp.sid(209), pg_temp.sid(133), pg_temp.sid(363)),
    (pg_temp.sid(209), pg_temp.sid(134), pg_temp.sid(364)),
    (pg_temp.sid(210), pg_temp.sid(171), pg_temp.sid(371)),
    (pg_temp.sid(210), pg_temp.sid(172), pg_temp.sid(372));

-- ------------------------------------------------------------------ Suất diễn
-- Giờ diễn 19:30 giờ VN (12:30 UTC).

SELECT pg_temp.add_showtime(pg_temp.sid(401), pg_temp.sid(201), pg_temp.sid(121),            -- ON_SALE
       date_trunc('day', now()) + interval '30 days 12 hours 30 minutes', now() - interval '7 days');
SELECT pg_temp.add_showtime(pg_temp.sid(402), pg_temp.sid(201), pg_temp.sid(121),            -- chưa mở bán
       date_trunc('day', now()) + interval '45 days 12 hours 30 minutes', now() + interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(403), pg_temp.sid(201), pg_temp.sid(121),            -- SALES_CLOSED
       now() + interval '2 hours', now() - interval '30 days', now() - interval '1 hour');
SELECT pg_temp.add_showtime(pg_temp.sid(404), pg_temp.sid(201), pg_temp.sid(121),            -- DRAFT → 404
       date_trunc('day', now()) + interval '60 days 12 hours 30 minutes', now() - interval '1 day', NULL, 'DRAFT');
SELECT pg_temp.add_showtime(pg_temp.sid(411), pg_temp.sid(202), pg_temp.sid(123),            -- ON_SALE, HCM
       date_trunc('day', now()) + interval '20 days 12 hours 30 minutes', now() - interval '10 days');
SELECT pg_temp.add_showtime(pg_temp.sid(412), pg_temp.sid(202), pg_temp.sid(125),            -- ON_SALE, DN
       date_trunc('day', now()) + interval '27 days 12 hours 30 minutes', now() - interval '10 days');
SELECT pg_temp.add_showtime(pg_temp.sid(421), pg_temp.sid(203), pg_temp.sid(122),            -- gần hết ghế
       date_trunc('day', now()) + interval '10 days 12 hours 30 minutes', now() - interval '20 days');
SELECT pg_temp.add_showtime(pg_temp.sid(431), pg_temp.sid(204), pg_temp.sid(124),            -- hết sạch ghế
       date_trunc('day', now()) + interval '14 days 12 hours 30 minutes', now() - interval '30 days');
SELECT pg_temp.add_showtime(pg_temp.sid(432), pg_temp.sid(204), pg_temp.sid(124),            -- FINISHED
       date_trunc('day', now()) - interval '3 days' + interval '12 hours 30 minutes', now() - interval '40 days');
SELECT pg_temp.add_showtime(pg_temp.sid(441), pg_temp.sid(205), pg_temp.sid(125),            -- GA còn 2 chỗ
       date_trunc('day', now()) + interval '12 days 12 hours 30 minutes', now() - interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(451), pg_temp.sid(206), pg_temp.sid(124),            -- CANCELLED
       date_trunc('day', now()) + interval '4 days 2 hours', now() - interval '5 days', NULL, 'CANCELLED');
SELECT pg_temp.add_showtime(pg_temp.sid(452), pg_temp.sid(206), pg_temp.sid(124),            -- ON_SALE, max 2 vé
       date_trunc('day', now()) + interval '6 days 2 hours', now() - interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(461), pg_temp.sid(207), pg_temp.sid(121),            -- của event DRAFT
       date_trunc('day', now()) + interval '15 days 12 hours 30 minutes', now() - interval '1 day');
SELECT pg_temp.add_showtime(pg_temp.sid(471), pg_temp.sid(208), pg_temp.sid(124),            -- của event CANCELLED
       date_trunc('day', now()) + interval '9 days 12 hours 30 minutes', now() - interval '20 days', NULL, 'CANCELLED');
SELECT pg_temp.add_showtime(pg_temp.sid(481), pg_temp.sid(209), pg_temp.sid(121),            -- ON_SALE, có VIP Box
       date_trunc('day', now()) + interval '3 days 11 hours', now() - interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(491), pg_temp.sid(210), pg_temp.sid(125),            -- ON_SALE, DN
       date_trunc('day', now()) + interval '8 days 11 hours', now() - interval '5 days');

-- Ghế bị khoá (không bán) ở suất 401: A6.
UPDATE showtime_seat SET status = 'BLOCKED'
WHERE showtime_id = pg_temp.sid(401) AND seat_id = pg_temp.seat_of(pg_temp.sid(131), 'A', 6);

-- ------------------------------------------------------------------ 15 sự kiện phụ để test phân trang /events (limit 20)

INSERT INTO event (id, title, description, category, poster_url, status, max_tickets_per_order, published_at)
SELECT pg_temp.sid(1000 + n),
       (ARRAY['Liveshow Acoustic', 'Stand-up Comedy', 'Lễ hội Ẩm thực', 'Đêm nhạc Indie', 'Hội chợ Sách'])[(n - 1) % 5 + 1]
           || ' #' || n,
       'Sự kiện mẫu số ' || n,
       (ARRAY['CONCERT', 'OTHER', 'OTHER', 'CONCERT', 'OTHER'])[(n - 1) % 5 + 1],
       'https://picsum.photos/seed/vingo' || (1000 + n) || '/600/800', 'PUBLISHED', 10, now() - n * interval '1 day'
FROM generate_series(1, 15) n;

INSERT INTO price_tier (id, event_id, name, price)
SELECT pg_temp.sid(2000 + n), pg_temp.sid(1000 + n), 'Vé chung', 100000 * ((n - 1) % 5 + 1)
FROM generate_series(1, 15) n;

-- Xoay vòng 3 sân có khu đứng: Quân khu 7 (HCM), Biển Đông (DN), Mỹ Đình (HN). Mọi khu đều có giá.
INSERT INTO zone_price (event_id, zone_id, price_tier_id)
SELECT pg_temp.sid(1000 + n), z.id, pg_temp.sid(2000 + n)
FROM generate_series(1, 15) n
JOIN zone z ON z.seat_layout_id = (ARRAY[pg_temp.sid(123), pg_temp.sid(125), pg_temp.sid(121)])[(n - 1) % 3 + 1];

SELECT pg_temp.add_showtime(pg_temp.sid(3000 + n), pg_temp.sid(1000 + n),
       (ARRAY[pg_temp.sid(123), pg_temp.sid(125), pg_temp.sid(121)])[(n - 1) % 3 + 1],
       date_trunc('day', now()) + n * interval '2 days' + interval '13 hours', now() - interval '3 days')
FROM generate_series(1, 15) n;

-- ------------------------------------------------------------------ Đơn hàng
-- buyer  = sid(2): đơn để test listMyOrders, getOrder, cancelOrder.
-- buyer2 = sid(3): đơn của người khác → buyer gọi sẽ nhận 404.

-- Suất 401: buyer2 đã mua A1–A2, đang giữ A3–A4; buyer3 có hold quá hạn ở A5 (coi là trống).
SELECT pg_temp.add_order(pg_temp.sid(501), 'SEED01', pg_temp.sid(3), pg_temp.sid(401), 'PAID', now() - interval '2 days');
SELECT pg_temp.order_seats(pg_temp.sid(501), pg_temp.sid(131), 'A', 1, 2, 'SOLD');

SELECT pg_temp.add_order(pg_temp.sid(502), 'SEED02', pg_temp.sid(3), pg_temp.sid(401), 'PENDING', now() + interval '1 hour');
SELECT pg_temp.order_seats(pg_temp.sid(502), pg_temp.sid(131), 'A', 3, 4, 'HELD', now() + interval '1 hour');

SELECT pg_temp.add_order(pg_temp.sid(503), 'SEED03', pg_temp.sid(4), pg_temp.sid(401), 'PENDING', now() - interval '5 minutes');
SELECT pg_temp.order_seats(pg_temp.sid(503), pg_temp.sid(131), 'A', 5, 5, 'HELD', now() - interval '5 minutes');

-- buyer: đơn PAID ở suất 411 (2 ghế VIP + 2 vé đứng).
SELECT pg_temp.add_order(pg_temp.sid(504), 'SEED04', pg_temp.sid(2), pg_temp.sid(411), 'PAID', now() - interval '3 days');
SELECT pg_temp.order_seats(pg_temp.sid(504), pg_temp.sid(151), 'A', 1, 2, 'SOLD');
SELECT pg_temp.order_standing(pg_temp.sid(504), pg_temp.sid(152), 2, 'sold');

-- buyer: đơn đã huỷ và đơn hết hạn (ghế đã nhả).
SELECT pg_temp.add_order(pg_temp.sid(505), 'SEED05', pg_temp.sid(2), pg_temp.sid(421), 'CANCELLED', now() - interval '4 days');
SELECT pg_temp.order_seats(pg_temp.sid(505), pg_temp.sid(142), 'F', 1, 1, NULL);

SELECT pg_temp.add_order(pg_temp.sid(506), 'SEED06', pg_temp.sid(2), pg_temp.sid(401), 'EXPIRED', now() - interval '5 days');
SELECT pg_temp.order_seats(pg_temp.sid(506), pg_temp.sid(132), 'D', 1, 1, NULL);

-- buyer: đơn NEEDS_REVIEW (tiền về sau hạn) ở suất 431.
SELECT pg_temp.add_order(pg_temp.sid(507), 'SEED07', pg_temp.sid(2), pg_temp.sid(431), 'NEEDS_REVIEW', now() - interval '6 days',
       'PAID_AFTER_EXPIRY');
SELECT pg_temp.order_seats(pg_temp.sid(507), pg_temp.sid(161), 'A', 1, 1, NULL);

-- Suất 431 hết sạch: buyer2 mua toàn bộ 40 ghế (ghi đè ghế A1 của đơn review, đơn đó không giữ ghế).
SELECT pg_temp.add_order(pg_temp.sid(510), 'SEED10', pg_temp.sid(3), pg_temp.sid(431), 'PAID', now() - interval '7 days');
SELECT pg_temp.order_seats(pg_temp.sid(510), pg_temp.sid(161), r, 1, 10, 'SOLD') FROM unnest(ARRAY['A', 'B', 'C', 'D']) r;

-- Suất 421 gần hết: buyer đang giữ E11–E12 (để test cancelOrder và "đã có đơn đang giữ"),
-- buyer2 mua hết phần còn lại trừ G9–G12 → còn đúng 4 ghế trống ở Tầng 2.
SELECT pg_temp.add_order(pg_temp.sid(508), 'SEED08', pg_temp.sid(2), pg_temp.sid(421), 'PENDING', now() + interval '1 hour');
SELECT pg_temp.order_seats(pg_temp.sid(508), pg_temp.sid(141), 'E', 11, 12, 'HELD', now() + interval '1 hour');

SELECT pg_temp.add_order(pg_temp.sid(509), 'SEED09', pg_temp.sid(3), pg_temp.sid(421), 'PAID', now() - interval '8 days');
SELECT pg_temp.order_seats(pg_temp.sid(509), pg_temp.sid(141), r, 1, 12, 'SOLD') FROM unnest(ARRAY['A', 'B', 'C', 'D']) r;
SELECT pg_temp.order_seats(pg_temp.sid(509), pg_temp.sid(141), 'E', 1, 10, 'SOLD');
SELECT pg_temp.order_seats(pg_temp.sid(509), pg_temp.sid(142), 'F', 1, 12, 'SOLD');
SELECT pg_temp.order_seats(pg_temp.sid(509), pg_temp.sid(142), 'G', 1, 8, 'SOLD');

-- Suất 441: GA (5 chỗ) đã bán 3 → giữ 3 vé sẽ NOT_ENOUGH_STOCK.
SELECT pg_temp.add_order(pg_temp.sid(511), 'SEED11', pg_temp.sid(3), pg_temp.sid(441), 'PAID', now() - interval '2 days');
SELECT pg_temp.order_standing(pg_temp.sid(511), pg_temp.sid(171), 3, 'sold');

COMMIT;

-- ------------------------------------------------------------------ Tóm tắt id để test
\o
\echo
\echo '== Kịch bản test (id đầy đủ = 01970000-0000-7000-8000-00000000NNNN) =='
SELECT 'showtime' AS loai, right(s.id::text, 4) AS nnnn, s.id, e.title,
       CASE WHEN s.status <> 'SCHEDULED' THEN s.status
            WHEN now() >= s.ends_at THEN 'FINISHED'
            WHEN now() < s.sale_opens_at THEN 'CHƯA MỞ BÁN'
            WHEN now() >= s.sale_closes_at THEN 'SALES_CLOSED'
            ELSE 'ON_SALE' END AS trang_thai,
       v.city
FROM showtime s JOIN event e ON e.id = s.event_id JOIN hall h ON h.id = s.hall_id JOIN venue v ON v.id = h.venue_id
WHERE s.id < pg_temp.sid(1000)
ORDER BY s.id;

SELECT 'order' AS loai, o.code, o.id, u.email AS chu_don, o.status, o.total_amount
FROM orders o JOIN users u ON u.id = o.user_id ORDER BY o.code;
