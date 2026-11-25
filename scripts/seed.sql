-- Dữ liệu mẫu cho máy dev (schema v2 - 31 bảng), đủ để gọi thử mọi API (search, booking, auth sessions/logout).
-- Chạy qua scripts/seed-local.sh (nạp SQL rồi xoá cache Redis). Chỉ dùng cho DB local.
--
-- CẢNH BÁO: script XOÁ SẠCH dữ liệu catalog, booking, payment, ticketing rồi nạp lại từ đầu.
-- Bảng user_account giữ nguyên, chỉ ghi đè các user seed và xoá phiên/idempotency của họ.
-- Chạy lại nhiều lần được; mọi mốc giờ tính từ now() nên mỗi lần chạy lại trạng thái "đang mở bán" vẫn đúng.
--
-- Id cố định dạng 01970000-0000-7000-8000-00000000NNNN, xem pg_temp.sid(NNNN).

\set ON_ERROR_STOP on
-- Ẩn kết quả rỗng của các lệnh SELECT pg_temp.*(...) bên dưới; bật lại ở phần tóm tắt cuối file.
\o /dev/null
BEGIN;

-- ------------------------------------------------------------------ Hàm phụ (chỉ sống trong phiên psql này)

CREATE FUNCTION pg_temp.sid(n int) RETURNS uuid LANGUAGE sql IMMUTABLE AS
$$ SELECT ('01970000-0000-7000-8000-' || lpad(n::text, 12, '0'))::uuid $$;

-- Tạo ghế cho khu ngồi: mỗi hàng per_row ghế.
CREATE FUNCTION pg_temp.add_seats(z uuid, rows_ text[], per_row int)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE sm uuid;
BEGIN
    SELECT seat_map_id INTO sm FROM zone WHERE id = z;
    INSERT INTO seat (seat_map_id, zone_id, row_label, number, zone_kind)
    SELECT sm, z, r, n, 'SEATED'::zone_kind
    FROM unnest(rows_) AS r, generate_series(1, per_row) AS n;
END $$;

CREATE FUNCTION pg_temp.seat_of(z uuid, r text, n int) RETURNS uuid LANGUAGE sql AS
$$ SELECT id FROM seat WHERE zone_id = z AND row_label = r AND number = n $$;

-- Suất diễn dài 2 giờ. sale_closes mặc định = lúc bắt đầu.
CREATE FUNCTION pg_temp.add_showtime(id_ uuid, ev uuid, layout uuid, starts timestamptz,
                                     sale_opens timestamptz, sale_closes timestamptz DEFAULT NULL,
                                     cancelled timestamptz DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO showtime (id, event_id, seat_map_id, starts_at, ends_at, sale_opens_at, sale_closes_at, cancelled_at)
    VALUES (id_, ev, layout, starts, starts + interval '2 hours', sale_opens, coalesce(sale_closes, starts), cancelled);

    -- Tồn kho: mỗi ghế một dòng AVAILABLE, mỗi khu đứng một dòng bộ đếm.
    INSERT INTO seat_inventory (showtime_id, seat_id, zone_id, seat_map_id)
    SELECT id_, s.id, s.zone_id, s.seat_map_id FROM seat s WHERE s.seat_map_id = layout;

    INSERT INTO standing_inventory (showtime_id, zone_id, seat_map_id, capacity)
    SELECT id_, z.id, z.seat_map_id, z.capacity FROM zone z WHERE z.seat_map_id = layout AND z.kind = 'STANDING';
END $$;

CREATE FUNCTION pg_temp.add_order(id_ uuid, code_ text, usr uuid, st uuid, status_ order_status,
                                  expires timestamptz, review text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ticket_order (id, code, user_id, showtime_id, status, total_amount, expires_at,
                              buyer_name, buyer_email, buyer_phone, paid_at, created_at)
    SELECT id_, code_, usr, st, status_, 0, expires, u.full_name, u.email, u.phone,
           CASE WHEN status_ IN ('PAID', 'NEEDS_REVIEW') THEN expires - interval '2 minutes' END,
           expires - interval '10 minutes'
    FROM user_account u WHERE u.id = usr;

    INSERT INTO order_status_history (order_id, from_status, to_status, actor_id, at)
    VALUES (id_, NULL, 'PENDING', usr, expires - interval '10 minutes');
    IF status_ <> 'PENDING' THEN
        INSERT INTO order_status_history (order_id, from_status, to_status, actor_id, reason, at)
        VALUES (id_, 'PENDING', status_, CASE WHEN status_ = 'CANCELLED' THEN usr END, review,
                expires - interval '2 minutes');
    END IF;
END $$;

-- Gắn ghế (hàng r, số từ a đến b) vào đơn: thêm order_seat và đổi trạng thái seat_inventory.
-- seat_status NULL = chỉ ghi order_seat, ghế giữ nguyên (đơn đã huỷ/hết hạn đã nhả ghế).
CREATE FUNCTION pg_temp.order_seats(ord uuid, z uuid, r text, a int, b int,
                                    seat_status seat_status, held_until_ timestamptz DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE st uuid; ev uuid;
BEGIN
    SELECT o.showtime_id, s.event_id INTO st, ev FROM ticket_order o JOIN showtime s ON s.id = o.showtime_id WHERE o.id = ord;

    INSERT INTO order_seat (order_id, showtime_id, seat_id, ticket_type_price_id, unit_price)
    SELECT ord, st, se.id, sp.id, sp.price
    FROM seat se
    JOIN event_zone ez ON ez.event_id = ev AND ez.zone_id = z
    JOIN LATERAL (
        SELECT id, price FROM ticket_type_price
        WHERE ticket_type_id = ez.ticket_type_id AND effective_from <= now()
        ORDER BY effective_from DESC LIMIT 1
    ) sp ON true
    WHERE se.zone_id = z AND se.row_label = r AND se.number BETWEEN a AND b;

    IF seat_status IS NOT NULL THEN
        UPDATE seat_inventory si SET status = seat_status, order_id = ord, held_until = held_until_
        FROM seat se
        WHERE si.showtime_id = st AND si.seat_id = se.id AND se.zone_id = z AND se.row_label = r
          AND se.number BETWEEN a AND b;
    END IF;
    UPDATE ticket_order SET total_amount = (
        coalesce((SELECT sum(unit_price) FROM order_seat WHERE order_id = ord), 0) +
        coalesce((SELECT sum(unit_price * quantity) FROM order_standing WHERE order_id = ord), 0)
    ) WHERE id = ord;
END $$;

-- Khu đứng: thêm order_standing và cộng bộ đếm held/sold (stock_col NULL = không đụng bộ đếm).
CREATE FUNCTION pg_temp.order_standing(ord uuid, z uuid, qty int, stock_col text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE st uuid; ev uuid;
BEGIN
    SELECT o.showtime_id, s.event_id INTO st, ev FROM ticket_order o JOIN showtime s ON s.id = o.showtime_id WHERE o.id = ord;

    INSERT INTO order_standing (order_id, showtime_id, zone_id, ticket_type_price_id, quantity, unit_price)
    SELECT ord, st, z, sp.id, qty, sp.price
    FROM event_zone ez
    JOIN LATERAL (
        SELECT id, price FROM ticket_type_price
        WHERE ticket_type_id = ez.ticket_type_id AND effective_from <= now()
        ORDER BY effective_from DESC LIMIT 1
    ) sp ON true
    WHERE ez.event_id = ev AND ez.zone_id = z;

    IF stock_col = 'sold' THEN
        UPDATE standing_inventory SET sold = sold + qty WHERE showtime_id = st AND zone_id = z;
    ELSIF stock_col = 'held' THEN
        UPDATE standing_inventory SET held = held + qty WHERE showtime_id = st AND zone_id = z;
    END IF;
    UPDATE ticket_order SET total_amount = (
        coalesce((SELECT sum(unit_price) FROM order_seat WHERE order_id = ord), 0) +
        coalesce((SELECT sum(unit_price * quantity) FROM order_standing WHERE order_id = ord), 0)
    ) WHERE id = ord;
END $$;

-- ------------------------------------------------------------------ Xoá dữ liệu cũ

TRUNCATE ticket_scan, ticket, payment_gateway_log, payment, order_status_history,
         order_seat, order_standing, seat_inventory, standing_inventory, ticket_order,
         event_zone, ticket_type_price, sku, ticket_type, showtime, event,
         seat, zone, seat_map, venue_map, venue CASCADE;

DELETE FROM login_session      WHERE user_id IN (SELECT pg_temp.sid(n) FROM generate_series(1, 5) n);
DELETE FROM idempotency_record WHERE user_id IN (SELECT pg_temp.sid(n) FROM generate_series(1, 5) n);
DELETE FROM otp_verification   WHERE user_id IN (SELECT pg_temp.sid(n) FROM generate_series(1, 5) n);

-- ------------------------------------------------------------------ Users và phiên
-- Mật khẩu Vingo@2026 (Argon2).

INSERT INTO user_account (id, email, phone, full_name, password_hash, role, email_verified_at, admin_locked) VALUES
    (pg_temp.sid(1), 'admin@vingo.local',  '+84900000001', 'Admin Vingo',        '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'ADMIN', now(), false),
    (pg_temp.sid(2), 'buyer@vingo.local',  '+84900000002', 'Nguyễn Văn Mua',     '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', now(), false),
    (pg_temp.sid(3), 'buyer2@vingo.local', '+84900000003', 'Trần Thị Khác',      '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', now(), false),
    (pg_temp.sid(4), 'buyer3@vingo.local', '+84900000004', 'Lê Văn Chưa Hồ Sơ',  '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', now(), false),
    (pg_temp.sid(5), 'locked@vingo.local', '+84900000005', 'Phạm Bị Khoá',       '$argon2id$v=19$m=16384,t=2,p=1$RcFCYFjO41/Yy1aVhfrhFA$DxphoNYrGaJkZEoh8AXZB6TmW/8MPSGalW7lpf0bNMw', 'BUYER', now(), true)
ON CONFLICT (id) DO UPDATE SET
    email = EXCLUDED.email,
    phone = EXCLUDED.phone,
    full_name = EXCLUDED.full_name,
    password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    email_verified_at = EXCLUDED.email_verified_at,
    phone_verified_at = NULL,
    admin_locked = EXCLUDED.admin_locked,
    deleted_at = NULL,
    failed_login_count = 0,
    locked_until = NULL;

-- Local clients create an individual session with scripts/dev-token.sh.

-- ------------------------------------------------------------------ Sân, nền sân, sơ đồ ghế

INSERT INTO venue (id, name, address, city) VALUES
    (pg_temp.sid(101), 'Sân vận động Mỹ Đình',   'Lê Đức Thọ, Nam Từ Liêm',    'HN'),
    (pg_temp.sid(102), 'Nhà hát Lớn Hà Nội',     '1 Tràng Tiền, Hoàn Kiếm',    'HN'),
    (pg_temp.sid(103), 'Sân vận động Quân khu 7', '202 Hoàng Văn Thụ, Tân Bình', 'HCM'),
    (pg_temp.sid(104), 'Nhà hát Hòa Bình',       '240 Ba Tháng Hai, Quận 10',  'HCM'),
    (pg_temp.sid(105), 'Công viên Biển Đông',    'Võ Nguyên Giáp, Sơn Trà',    'DN');

INSERT INTO venue_map (id, venue_id, version, svg)
SELECT pg_temp.sid(110 + n), pg_temp.sid(100 + n), 1,
       '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"><rect id="BACKGROUND" width="1000" height="600"/></svg>'
FROM generate_series(1, 5) n;

INSERT INTO seat_map (id, venue_id, venue_map_id, version, name, layout_type, svg, view_box) VALUES
    (pg_temp.sid(121), pg_temp.sid(101), pg_temp.sid(111), 1, 'Sơ đồ Mỹ Đình',   'FOOTBALL', '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"/>', ARRAY[0,0,1000,600]),
    (pg_temp.sid(122), pg_temp.sid(102), pg_temp.sid(112), 1, 'Sơ đồ Nhà hát Lớn', 'CONCERT',  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"/>', ARRAY[0,0,1000,600]),
    (pg_temp.sid(123), pg_temp.sid(103), pg_temp.sid(113), 1, 'Sơ đồ Quân khu 7', 'CONCERT',  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"/>', ARRAY[0,0,1000,600]),
    (pg_temp.sid(124), pg_temp.sid(104), pg_temp.sid(114), 1, 'Sơ đồ Hòa Bình',   'CONCERT',  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"/>', ARRAY[0,0,1000,600]),
    (pg_temp.sid(125), pg_temp.sid(105), pg_temp.sid(115), 1, 'Sơ đồ Biển Đông',  'CONCERT',  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"/>', ARRAY[0,0,1000,600]),
    (pg_temp.sid(126), pg_temp.sid(105), pg_temp.sid(115), 2, 'Sơ đồ Biển Đông Bóng đá', 'FOOTBALL', '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600"/>', ARRAY[0,0,1000,600]);

INSERT INTO zone (id, seat_map_id, code, name, kind, capacity) VALUES
    -- L1 Mỹ Đình (121)
    (pg_temp.sid(131), pg_temp.sid(121), 'A',        'A',        'SEATED',   NULL),
    (pg_temp.sid(132), pg_temp.sid(121), 'B',        'B',        'SEATED',   NULL),
    (pg_temp.sid(133), pg_temp.sid(121), 'GA',       'Khu đứng', 'STANDING', 200),
    (pg_temp.sid(134), pg_temp.sid(121), 'VIP-BOX',  'VIP Box',  'SEATED',   NULL),
    -- L2 Nhà hát Lớn (122)
    (pg_temp.sid(141), pg_temp.sid(122), 'T1',       'Tầng 1',   'SEATED',   NULL),
    (pg_temp.sid(142), pg_temp.sid(122), 'T2',       'Tầng 2',   'SEATED',   NULL),
    -- L3 Quân khu 7 (123)
    (pg_temp.sid(151), pg_temp.sid(123), 'A',        'Khán đài A', 'SEATED', NULL),
    (pg_temp.sid(152), pg_temp.sid(123), 'PITCH',    'Sân cỏ',   'STANDING', 500),
    -- L4 Hòa Bình (124)
    (pg_temp.sid(161), pg_temp.sid(124), 'MAIN',     'Sảnh chính', 'SEATED', NULL),
    -- L5 Biển Đông Concert (125): GA chỉ 5 chỗ để test hết chỗ
    (pg_temp.sid(171), pg_temp.sid(125), 'GA',       'GA',       'STANDING', 5),
    (pg_temp.sid(172), pg_temp.sid(125), 'FANZONE',  'Fanzone',  'STANDING', 1000),
    -- L6 Biển Đông Football (126)
    (pg_temp.sid(173), pg_temp.sid(126), 'GA',       'GA',       'STANDING', 200),
    (pg_temp.sid(174), pg_temp.sid(126), 'FANZONE',  'Fanzone',  'STANDING', 500);

SELECT pg_temp.add_seats(pg_temp.sid(131), ARRAY['A', 'B', 'C'], 10);
SELECT pg_temp.add_seats(pg_temp.sid(132), ARRAY['D', 'E'], 10);
SELECT pg_temp.add_seats(pg_temp.sid(134), ARRAY['V'], 4);
SELECT pg_temp.add_seats(pg_temp.sid(141), ARRAY['A', 'B', 'C', 'D', 'E'], 12);
SELECT pg_temp.add_seats(pg_temp.sid(142), ARRAY['F', 'G'], 12);
SELECT pg_temp.add_seats(pg_temp.sid(151), ARRAY['A', 'B'], 10);
SELECT pg_temp.add_seats(pg_temp.sid(161), ARRAY['A', 'B', 'C', 'D'], 10);

-- SVG mẫu dùng đúng mã khu/hàng/ghế để có thể chọn vé trên web.
WITH placed_zones AS (
    SELECT z.*,
           40 + ((row_number() OVER (PARTITION BY seat_map_id ORDER BY id) - 1) % 2) * 480 AS x,
           35 + ((row_number() OVER (PARTITION BY seat_map_id ORDER BY id) - 1) / 2) * 285 AS y
    FROM zone z
), placed_seats AS (
    SELECT s.*,
           dense_rank() OVER (PARTITION BY zone_id ORDER BY row_label) AS row_number
    FROM seat s
), shapes AS (
    SELECT z.seat_map_id, z.id,
           format('<rect id="ZONE__%s__%s%s" x="%s" y="%s" width="440" height="245" rx="10" fill="#dbeafe"/>',
                  z.code, z.kind, CASE WHEN z.kind = 'STANDING' THEN '__' || z.capacity ELSE '' END, z.x, z.y)
           || CASE WHEN z.kind = 'SEATED' THEN
               format('<g id="CONTENT__%s">%s<path id="PITCH_SIDE__%s" d="M%s %s h420" stroke="#64748b"/></g>',
                      z.code,
                      coalesce((SELECT string_agg(format(
                          '<circle id="SEAT__%s__%s__%s" cx="%s" cy="%s" r="7" fill="#6366f1"/>',
                          z.code, s.row_label, s.number, z.x + 18 + s.number * 30, z.y + 18 + s.row_number * 32
                      ), '' ORDER BY s.row_label, s.number) FROM placed_seats s WHERE s.zone_id = z.id), ''),
                      z.code, z.x + 10, z.y + 230)
              ELSE '' END
           || format('<text x="%s" y="%s" fill="#0f172a">%s</text>', z.x + 10, z.y + 20, z.code) AS svg
    FROM placed_zones z
), rendered AS (
    SELECT seat_map_id,
           '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600">'
           || string_agg(svg, '' ORDER BY id) || '</svg>' AS svg
    FROM shapes GROUP BY seat_map_id
)
UPDATE seat_map m SET svg = r.svg FROM rendered r WHERE r.seat_map_id = m.id;

-- ------------------------------------------------------------------ Sự kiện, SKU, giá, event_zone

INSERT INTO event (id, title, description, category, home_team, away_team, league, round, poster_mobile_url, poster_desktop_url, max_tickets_per_order, seat_map_id, status) VALUES
    (pg_temp.sid(201), 'Việt Nam vs Thái Lan', 'Vòng loại Asian Cup, sân Mỹ Đình', 'FOOTBALL',
     'Việt Nam', 'Thái Lan', 'Vòng loại Asian Cup', 'Lượt về',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/4/4d/IRN-VIETNAM_20190112_Asian_Cup_5.jpg/960px-IRN-VIETNAM_20190112_Asian_Cup_5.jpg',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/4/4d/IRN-VIETNAM_20190112_Asian_Cup_5.jpg/960px-IRN-VIETNAM_20190112_Asian_Cup_5.jpg',
     6, pg_temp.sid(121), 'PUBLISHED'),
    (pg_temp.sid(202), 'Sơn Tùng M-TP Sky Tour 2026', 'Tour diễn xuyên Việt của Sơn Tùng M-TP', 'CONCERT',
     NULL, NULL, NULL, NULL,
     'https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Beach-Please-2022-crowd-stage-lights-night-performance.jpg/960px-Beach-Please-2022-crowd-stage-lights-night-performance.jpg',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Beach-Please-2022-crowd-stage-lights-night-performance.jpg/960px-Beach-Please-2022-crowd-stage-lights-night-performance.jpg',
     4, pg_temp.sid(123), 'PUBLISHED'),
    (pg_temp.sid(203), 'Đêm nhạc Lôi Vũ', 'Chương trình nghệ thuật kinh điển', 'CONCERT',
     NULL, NULL, NULL, NULL,
     'https://upload.wikimedia.org/wikipedia/commons/thumb/0/01/Inside_Hanoi_Opera_House.JPG/960px-Inside_Hanoi_Opera_House.JPG',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/0/01/Inside_Hanoi_Opera_House.JPG/960px-Inside_Hanoi_Opera_House.JPG',
     8, pg_temp.sid(122), 'PUBLISHED'),
    (pg_temp.sid(204), 'Hòa nhạc Giao hưởng Mùa Thu', 'Dàn nhạc Giao hưởng TP.HCM', 'CONCERT',
     NULL, NULL, NULL, NULL,
     'https://upload.wikimedia.org/wikipedia/commons/thumb/b/be/CBSO_Youth_Orchestra_in_Symphony_Hall_-_geograph.org.uk_-_7422392.jpg/960px-CBSO_Youth_Orchestra_in_Symphony_Hall_-_geograph.org.uk_-_7422392.jpg',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/b/be/CBSO_Youth_Orchestra_in_Symphony_Hall_-_geograph.org.uk_-_7422392.jpg/960px-CBSO_Youth_Orchestra_in_Symphony_Hall_-_geograph.org.uk_-_7422392.jpg',
     10, pg_temp.sid(124), 'PUBLISHED'),
    (pg_temp.sid(205), 'Đêm nhạc Trịnh Công Sơn', 'Đêm nhạc ngoài trời bên biển', 'CONCERT',
     NULL, NULL, NULL, NULL,
     'https://upload.wikimedia.org/wikipedia/commons/thumb/8/8f/Reggae_2_Jungle_Beach_stage%2C_Beats_for_Love_2019_02.jpg/960px-Reggae_2_Jungle_Beach_stage%2C_Beats_for_Love_2019_02.jpg',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/8/8f/Reggae_2_Jungle_Beach_stage%2C_Beats_for_Love_2019_02.jpg/960px-Reggae_2_Jungle_Beach_stage%2C_Beats_for_Love_2019_02.jpg',
     10, pg_temp.sid(125), 'PUBLISHED'),
    (pg_temp.sid(206), 'Liveshow Acoustic Cuối Tuần', 'Đêm nhạc mộc với nghệ sĩ khách mời', 'CONCERT',
     NULL, NULL, NULL, NULL,
     NULL, NULL,
     2, pg_temp.sid(124), 'PUBLISHED'),
    (pg_temp.sid(207), 'Sự kiện nháp chưa công bố', 'Không được hiện ở search', 'FOOTBALL',
     'Đội A', 'Đội B', 'Giao hữu', 'Trận 1',
     NULL, NULL,
     10, pg_temp.sid(121), 'DRAFT'),
    (pg_temp.sid(208), 'Đêm nhạc đã huỷ', 'Sự kiện đã huỷ, không hiện ở search', 'CONCERT',
     NULL, NULL, NULL, NULL,
     NULL, NULL,
     10, pg_temp.sid(124), 'CANCELLED'),
    (pg_temp.sid(209), 'Hà Nội FC vs Hải Phòng FC', 'V-League vòng 8', 'FOOTBALL',
     'Hà Nội FC', 'Hải Phòng FC', 'V-League', 'Vòng 8',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/4/41/S%C3%A2n_v%E1%BA%ADn_%C4%91%E1%BB%99ng_H%C3%A0ng_%C4%90%E1%BA%ABy.jpg/960px-S%C3%A2n_v%E1%BA%ADn_%C4%91%E1%BB%99ng_H%C3%A0ng_%C4%90%E1%BA%ABy.jpg',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/4/41/S%C3%A2n_v%E1%BA%ADn_%C4%91%E1%BB%99ng_H%C3%A0ng_%C4%90%E1%BA%ABy.jpg/960px-S%C3%A2n_v%E1%BA%ADn_%C4%91%E1%BB%99ng_H%C3%A0ng_%C4%90%E1%BA%ABy.jpg',
     10, pg_temp.sid(121), 'PUBLISHED'),
    (pg_temp.sid(210), 'SHB Đà Nẵng vs Hoàng Anh Gia Lai', 'V-League vòng 8', 'FOOTBALL',
     'SHB Đà Nẵng', 'Hoàng Anh Gia Lai', 'V-League', 'Vòng 8',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/2012-13_Europa_League_final_-_Chelsea_FC_vs._SL_Benfica%2C_Amsterdam_ArenA%2C_kick-off.jpg/960px-2012-13_Europa_League_final_-_Chelsea_FC_vs._SL_Benfica%2C_Amsterdam_ArenA%2C_kick-off.jpg',
     'https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/2012-13_Europa_League_final_-_Chelsea_FC_vs._SL_Benfica%2C_Amsterdam_ArenA%2C_kick-off.jpg/960px-2012-13_Europa_League_final_-_Chelsea_FC_vs._SL_Benfica%2C_Amsterdam_ArenA%2C_kick-off.jpg',
     10, pg_temp.sid(126), 'PUBLISHED');

INSERT INTO ticket_type (id, event_id, name, color) VALUES
    (pg_temp.sid(301), pg_temp.sid(201), 'Hạng A',  '#E4572E'),
    (pg_temp.sid(302), pg_temp.sid(201), 'Hạng B',  '#F3A712'),
    (pg_temp.sid(303), pg_temp.sid(201), 'Đứng',    '#4C956C'),
    (pg_temp.sid(311), pg_temp.sid(202), 'VIP',     '#8E44AD'),
    (pg_temp.sid(312), pg_temp.sid(202), 'GA',      '#2E86AB'),
    (pg_temp.sid(321), pg_temp.sid(203), 'Tầng 1',  '#C0392B'),
    (pg_temp.sid(322), pg_temp.sid(203), 'Tầng 2',  '#16A085'),
    (pg_temp.sid(331), pg_temp.sid(204), 'Thường',  '#34495E'),
    (pg_temp.sid(341), pg_temp.sid(205), 'GA',      '#D35400'),
    (pg_temp.sid(342), pg_temp.sid(205), 'Fanzone', '#27AE60'),
    (pg_temp.sid(351), pg_temp.sid(206), 'Vé lớp',  '#7F8C8D'),
    (pg_temp.sid(355), pg_temp.sid(207), 'Thường',  '#34495E'),
    (pg_temp.sid(361), pg_temp.sid(209), 'Hạng A',  '#E4572E'),
    (pg_temp.sid(362), pg_temp.sid(209), 'Hạng B',  '#F3A712'),
    (pg_temp.sid(363), pg_temp.sid(209), 'Đứng',    '#4C956C'),
    (pg_temp.sid(364), pg_temp.sid(209), 'VIP Box', '#8E44AD'),
    (pg_temp.sid(371), pg_temp.sid(210), 'GA',      '#D35400'),
    (pg_temp.sid(372), pg_temp.sid(210), 'Fanzone', '#27AE60');

INSERT INTO ticket_type_price (id, ticket_type_id, price, effective_from, created_by, reason) VALUES
    (pg_temp.sid(301), pg_temp.sid(301),  500000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(302), pg_temp.sid(302),  350000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(303), pg_temp.sid(303),  200000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(311), pg_temp.sid(311), 3000000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(312), pg_temp.sid(312), 1200000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(321), pg_temp.sid(321),  450000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(322), pg_temp.sid(322),  300000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(331), pg_temp.sid(331),  600000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(341), pg_temp.sid(341),  250000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(342), pg_temp.sid(342),  150000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(351), pg_temp.sid(351),  400000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(355), pg_temp.sid(355),  100000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(361), pg_temp.sid(361),  250000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(362), pg_temp.sid(362),  150000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(363), pg_temp.sid(363),   80000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(364), pg_temp.sid(364), 1000000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(371), pg_temp.sid(371),  200000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'),
    (pg_temp.sid(372), pg_temp.sid(372),  100000, now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán');

-- E1 cố ý không gán giá cho VIP Box (134) để test "suất chưa có giá cho khu này".
INSERT INTO event_zone (event_id, zone_id, seat_map_id, ticket_type_id) VALUES
    (pg_temp.sid(201), pg_temp.sid(131), pg_temp.sid(121), pg_temp.sid(301)),
    (pg_temp.sid(201), pg_temp.sid(132), pg_temp.sid(121), pg_temp.sid(302)),
    (pg_temp.sid(201), pg_temp.sid(133), pg_temp.sid(121), pg_temp.sid(303)),
    (pg_temp.sid(202), pg_temp.sid(151), pg_temp.sid(123), pg_temp.sid(311)),
    (pg_temp.sid(202), pg_temp.sid(152), pg_temp.sid(123), pg_temp.sid(312)),
    (pg_temp.sid(203), pg_temp.sid(141), pg_temp.sid(122), pg_temp.sid(321)),
    (pg_temp.sid(203), pg_temp.sid(142), pg_temp.sid(122), pg_temp.sid(322)),
    (pg_temp.sid(204), pg_temp.sid(161), pg_temp.sid(124), pg_temp.sid(331)),
    (pg_temp.sid(205), pg_temp.sid(171), pg_temp.sid(125), pg_temp.sid(341)),
    (pg_temp.sid(205), pg_temp.sid(172), pg_temp.sid(125), pg_temp.sid(342)),
    (pg_temp.sid(206), pg_temp.sid(161), pg_temp.sid(124), pg_temp.sid(351)),
    (pg_temp.sid(207), pg_temp.sid(131), pg_temp.sid(121), pg_temp.sid(355)),
    (pg_temp.sid(209), pg_temp.sid(131), pg_temp.sid(121), pg_temp.sid(361)),
    (pg_temp.sid(209), pg_temp.sid(132), pg_temp.sid(121), pg_temp.sid(362)),
    (pg_temp.sid(209), pg_temp.sid(133), pg_temp.sid(121), pg_temp.sid(363)),
    (pg_temp.sid(209), pg_temp.sid(134), pg_temp.sid(121), pg_temp.sid(364)),
    (pg_temp.sid(210), pg_temp.sid(173), pg_temp.sid(126), pg_temp.sid(371)),
    (pg_temp.sid(210), pg_temp.sid(174), pg_temp.sid(126), pg_temp.sid(372));

-- ------------------------------------------------------------------ Suất diễn
-- Giờ diễn 19:30 giờ VN (12:30 UTC).

SELECT pg_temp.add_showtime(pg_temp.sid(401), pg_temp.sid(201), pg_temp.sid(121),            -- ON_SALE
       date_trunc('day', now()) + interval '30 days 12 hours 30 minutes', now() - interval '7 days');
SELECT pg_temp.add_showtime(pg_temp.sid(402), pg_temp.sid(201), pg_temp.sid(121),            -- chưa mở bán
       date_trunc('day', now()) + interval '45 days 12 hours 30 minutes', now() + interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(403), pg_temp.sid(201), pg_temp.sid(121),            -- SALES_CLOSED
       now() + interval '2 hours', now() - interval '30 days', now() - interval '1 hour');
SELECT pg_temp.add_showtime(pg_temp.sid(411), pg_temp.sid(202), pg_temp.sid(123),            -- ON_SALE, HCM
       date_trunc('day', now()) + interval '20 days 12 hours 30 minutes', now() - interval '10 days');
SELECT pg_temp.add_showtime(pg_temp.sid(421), pg_temp.sid(203), pg_temp.sid(122),            -- gần hết ghế
       date_trunc('day', now()) + interval '10 days 12 hours 30 minutes', now() - interval '20 days');
SELECT pg_temp.add_showtime(pg_temp.sid(431), pg_temp.sid(204), pg_temp.sid(124),            -- hết sạch ghế
       date_trunc('day', now()) + interval '14 days 12 hours 30 minutes', now() - interval '30 days');
SELECT pg_temp.add_showtime(pg_temp.sid(432), pg_temp.sid(204), pg_temp.sid(124),            -- FINISHED
       date_trunc('day', now()) - interval '3 days' + interval '12 hours 30 minutes', now() - interval '40 days');
SELECT pg_temp.add_showtime(pg_temp.sid(441), pg_temp.sid(205), pg_temp.sid(125),            -- GA còn 2 chỗ
       date_trunc('day', now()) + interval '12 days 12 hours 30 minutes', now() - interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(451), pg_temp.sid(206), pg_temp.sid(124),            -- CANCELLED
       date_trunc('day', now()) + interval '4 days 2 hours', now() - interval '5 days', NULL, now() - interval '1 day');
SELECT pg_temp.add_showtime(pg_temp.sid(452), pg_temp.sid(206), pg_temp.sid(124),            -- ON_SALE, max 2 vé
       date_trunc('day', now()) + interval '6 days 2 hours', now() - interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(461), pg_temp.sid(207), pg_temp.sid(121),            -- của event DRAFT
       date_trunc('day', now()) + interval '15 days 12 hours 30 minutes', now() - interval '1 day');
SELECT pg_temp.add_showtime(pg_temp.sid(471), pg_temp.sid(208), pg_temp.sid(124),            -- của event CANCELLED
       date_trunc('day', now()) + interval '9 days 12 hours 30 minutes', now() - interval '20 days', NULL, now() - interval '10 days');
SELECT pg_temp.add_showtime(pg_temp.sid(481), pg_temp.sid(209), pg_temp.sid(121),            -- ON_SALE, có VIP Box
       date_trunc('day', now()) + interval '3 days 11 hours', now() - interval '5 days');
SELECT pg_temp.add_showtime(pg_temp.sid(491), pg_temp.sid(210), pg_temp.sid(126),            -- ON_SALE, DN
       date_trunc('day', now()) + interval '8 days 11 hours', now() - interval '5 days');

-- Ghế bị khoá (không bán) ở suất 401: A6.
UPDATE seat_inventory SET status = 'BLOCKED'
WHERE showtime_id = pg_temp.sid(401) AND seat_id = pg_temp.seat_of(pg_temp.sid(131), 'A', 6);

-- ------------------------------------------------------------------ 15 sự kiện phụ để test phân trang /events (limit 20)

INSERT INTO event (id, title, description, category, poster_mobile_url, poster_desktop_url, seat_map_id, status, max_tickets_per_order)
SELECT pg_temp.sid(1000 + n),
       (ARRAY['Liveshow Acoustic', 'Stand-up Concert', 'Đêm nhạc Đường phố', 'Đêm nhạc Indie', 'Hòa nhạc Mùa Xuân'])[(n - 1) % 5 + 1]
           || ' #' || n,
       'Sự kiện mẫu số ' || n,
       'CONCERT',
       (ARRAY[
           'https://upload.wikimedia.org/wikipedia/commons/0/01/Concert_of_classical_guitar_in_Ivry-sur-Seine.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/a/a3/Sitcom_Live_%28141520%29.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/thumb/2/2e/DZ6_0930_Skewers_of_sizzling_street_food_glow_under_neon_lights_as_a_bustling_night_market_hums_in_the_background.jpg/960px-DZ6_0930_Skewers_of_sizzling_street_food_glow_under_neon_lights_as_a_bustling_night_market_hums_in_the_background.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/thumb/f/f4/LAKE_playing_at_The_Hideout_in_Chicago%2C_Illinois%2C_USA_%282025-04-20%29.jpg/960px-LAKE_playing_at_The_Hideout_in_Chicago%2C_Illinois%2C_USA_%282025-04-20%29.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/Pragati_Maidan_Book_Fair_4.jpg/960px-Pragati_Maidan_Book_Fair_4.jpg'
       ])[(n - 1) % 5 + 1],
       (ARRAY[
           'https://upload.wikimedia.org/wikipedia/commons/0/01/Concert_of_classical_guitar_in_Ivry-sur-Seine.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/a/a3/Sitcom_Live_%28141520%29.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/thumb/2/2e/DZ6_0930_Skewers_of_sizzling_street_food_glow_under_neon_lights_as_a_bustling_night_market_hums_in_the_background.jpg/960px-DZ6_0930_Skewers_of_sizzling_street_food_glow_under_neon_lights_as_a_bustling_night_market_hums_in_the_background.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/thumb/f/f4/LAKE_playing_at_The_Hideout_in_Chicago%2C_Illinois%2C_USA_%282025-04-20%29.jpg/960px-LAKE_playing_at_The_Hideout_in_Chicago%2C_Illinois%2C_USA_%282025-04-20%29.jpg',
           'https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/Pragati_Maidan_Book_Fair_4.jpg/960px-Pragati_Maidan_Book_Fair_4.jpg'
       ])[(n - 1) % 5 + 1],
       (ARRAY[pg_temp.sid(123), pg_temp.sid(125), pg_temp.sid(122)])[(n - 1) % 3 + 1],
       'PUBLISHED', 10
FROM generate_series(1, 15) n;

INSERT INTO ticket_type (id, event_id, name, color)
SELECT pg_temp.sid(2000 + n), pg_temp.sid(1000 + n), 'Vé chung', '#34495E'
FROM generate_series(1, 15) n;

INSERT INTO ticket_type_price (id, ticket_type_id, price, effective_from, created_by, reason)
SELECT pg_temp.sid(2000 + n), pg_temp.sid(2000 + n), 100000 * ((n - 1) % 5 + 1), now() - interval '30 days', pg_temp.sid(1), 'Giá mở bán'
FROM generate_series(1, 15) n;

INSERT INTO event_zone (event_id, zone_id, seat_map_id, ticket_type_id)
SELECT pg_temp.sid(1000 + n), z.id, z.seat_map_id, pg_temp.sid(2000 + n)
FROM generate_series(1, 15) n
JOIN zone z ON z.seat_map_id = (ARRAY[pg_temp.sid(123), pg_temp.sid(125), pg_temp.sid(122)])[(n - 1) % 3 + 1];

SELECT pg_temp.add_showtime(pg_temp.sid(3000 + n), pg_temp.sid(1000 + n),
       (ARRAY[pg_temp.sid(123), pg_temp.sid(125), pg_temp.sid(122)])[(n - 1) % 3 + 1],
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

-- Suất 431 hết sạch: buyer2 mua toàn bộ 40 ghế.
SELECT pg_temp.add_order(pg_temp.sid(510), 'SEED10', pg_temp.sid(3), pg_temp.sid(431), 'PAID', now() - interval '7 days');
SELECT pg_temp.order_seats(pg_temp.sid(510), pg_temp.sid(161), r, 1, 10, 'SOLD') FROM unnest(ARRAY['A', 'B', 'C', 'D']) r;

-- Suất 421 gần hết: buyer đang giữ E11–E12, buyer2 mua hết phần còn lại trừ G9–G12 → còn đúng 4 ghế trống ở Tầng 2.
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
       CASE WHEN s.cancelled_at IS NOT NULL OR e.status = 'CANCELLED' THEN 'CANCELLED'
            WHEN e.status = 'DRAFT' THEN 'DRAFT'
            WHEN now() >= s.ends_at THEN 'FINISHED'
            WHEN now() < s.sale_opens_at THEN 'CHƯA MỞ BÁN'
            WHEN now() >= s.sale_closes_at THEN 'SALES_CLOSED'
            ELSE 'ON_SALE' END AS trang_thai,
       v.city
FROM showtime s
JOIN event e ON e.id = s.event_id
JOIN seat_map sm ON sm.id = s.seat_map_id
JOIN venue v ON v.id = sm.venue_id
WHERE s.id < pg_temp.sid(1000)
ORDER BY s.id;

SELECT 'order' AS loai, o.code, o.id, u.email AS chu_don, o.status, o.total_amount
FROM ticket_order o JOIN user_account u ON u.id = o.user_id ORDER BY o.code;
