-- Xoá schema cũ (v1) để dựng schema v2. Dữ liệu cũ trên máy local được bỏ, không chuyển dữ liệu.
-- Giữ nguyên flyway_schema_history; extension (unaccent, pg_trgm) để nguyên, chỉ xoá đối tượng do migration tạo.
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT viewname FROM pg_views WHERE schemaname = 'public' LOOP
        EXECUTE format('DROP VIEW IF EXISTS public.%I CASCADE', r.viewname);
    END LOOP;
    FOR r IN SELECT tablename FROM pg_tables
             WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' LOOP
        EXECUTE format('DROP TABLE IF EXISTS public.%I CASCADE', r.tablename);
    END LOOP;
    FOR r IN SELECT p.oid::regprocedure AS sig FROM pg_proc p
             JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'public'
               AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e') LOOP
        EXECUTE format('DROP FUNCTION IF EXISTS %s CASCADE', r.sig);
    END LOOP;
    FOR r IN SELECT t.typname FROM pg_type t
             JOIN pg_namespace n ON n.oid = t.typnamespace
             WHERE n.nspname = 'public' AND t.typtype = 'e'
               AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = t.oid AND d.deptype = 'e') LOOP
        EXECUTE format('DROP TYPE IF EXISTS public.%I CASCADE', r.typname);
    END LOOP;
END $$;

-- ============================ SCHEMA V2 ============================
-- =====================================================================
-- Vingo — thiết kế lại database (bản đề xuất v2, 2026-11-18)
--
-- Quy ước:
--   * Khoá chính uuidv7() (PostgreSQL 18), thời gian timestamptz (UTC), tiền bigint VND
--   * Enum Postgres cho tập giá trị cố định; text + CHECK cho danh sách còn mở rộng
--   * Không trigger: luật nghiệp vụ ở service, toàn vẹn quan hệ bằng FK ghép
--   * Không ON DELETE CASCADE trên dữ liệu tiền/vé
--
-- Quyết định đã chốt:
--   * 1 admin, không organizer; 1 event nhiều showtime; zone.kind = SEATED | STANDING
--   * SKU thuộc event, gom nhiều zone, mỗi zone thuộc đúng 1 SKU (event_zone)
--   * Ghế: khoá bi quan từng ghế + Implicit Status (HELD và held_until < now() = trống)
--   * Khu đứng: bộ đếm sold/held, cập nhật có điều kiện, chạy cuối transaction
--   * Không bộ đếm cho khu ngồi (không có virtual waiting room)
--   * Dòng đơn tách order_seat / order_standing; 1 vé = 1 ghế hoặc 1 suất đứng
--   * IPN là nơi duy nhất chốt bán; phát vé đồng bộ trong transaction IPN
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE FUNCTION immutable_city_text(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT CASE $1
                     WHEN 'HN' THEN 'Hà Nội Hanoi HN'
                     WHEN 'HCM' THEN 'TP. Hồ Chí Minh Sài Gòn Saigon HCM'
                     WHEN 'DN' THEN 'Đà Nẵng Da Nang DN'
                     ELSE coalesce($1, '')
                 END $$;

-- ============================ ENUM ============================
CREATE TYPE user_role          AS ENUM ('BUYER', 'ADMIN');
CREATE TYPE otp_purpose        AS ENUM ('VERIFY', 'RESET', 'PHONE');   -- VERIFY/RESET gửi email, PHONE gửi SMS
CREATE TYPE contact_channel    AS ENUM ('EMAIL', 'SMS');
CREATE TYPE zone_kind          AS ENUM ('SEATED', 'STANDING');
CREATE TYPE event_status       AS ENUM ('DRAFT', 'PUBLISHED', 'CANCELLED');
CREATE TYPE seat_status        AS ENUM ('AVAILABLE', 'HELD', 'SOLD', 'BLOCKED');
CREATE TYPE order_status       AS ENUM ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'NEEDS_REVIEW', 'REJECTED');
CREATE TYPE payment_status     AS ENUM ('OPEN', 'SUCCEEDED', 'FAILED', 'EXPIRED');
CREATE TYPE payment_source     AS ENUM ('IPN', 'QUERY');
CREATE TYPE payment_result     AS ENUM ('SUCCESS', 'FAILED');
CREATE TYPE ticket_status      AS ENUM ('ISSUED', 'USED', 'VOID');
CREATE TYPE scan_result        AS ENUM ('OK', 'ALREADY_USED', 'TICKET_VOID', 'WRONG_SHOWTIME', 'INVALID_QR');
CREATE TYPE outbox_status      AS ENUM ('PENDING', 'SENT', 'FAILED');

-- ============================ AUTH ============================
CREATE TABLE user_account (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    email                text        NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND length(email) <= 254),
    phone                text        CHECK (phone ~ '^\+[1-9][0-9]{8,14}$'),   -- unique chỉ khi đã xác minh
    full_name            text        NOT NULL CHECK (full_name = btrim(full_name) AND length(full_name) BETWEEN 1 AND 120),
    avatar_url           text        CHECK (avatar_url ~ '^https://' AND length(avatar_url) <= 2048),
    password_hash        text,                                   -- Argon2; NULL nếu chỉ đăng nhập Google
    google_sub           text        UNIQUE,
    role                 user_role   NOT NULL DEFAULT 'BUYER',
    email_verified_at    timestamptz,                            -- NULL = chưa xác minh email
    phone_verified_at    timestamptz,                            -- NULL = chưa xác minh SĐT (SMS OTP)
    admin_locked         boolean     NOT NULL DEFAULT false,
    auth_version         int         NOT NULL DEFAULT 0 CHECK (auth_version >= 0),   -- tăng để vô hiệu JWT cũ
    failed_login_count   smallint    NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    last_failed_login_at timestamptz,
    locked_until         timestamptz,
    deleted_at           timestamptz,                            -- khác NULL = đã xoá (ẩn danh hoá)
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL OR deleted_at IS NOT NULL),
    CHECK (phone_verified_at IS NULL OR phone IS NOT NULL)
);
-- Nhiều người có thể nhập cùng số, chỉ một người xác minh được
CREATE UNIQUE INDEX user_account_verified_phone_uk ON user_account (phone) WHERE phone_verified_at IS NOT NULL;

-- Phiên đăng nhập, mỗi thiết bị một dòng. Refresh token xoay vòng tại chỗ (chỉ lưu hash).
-- Đăng xuất = xoá dòng; đăng xuất tất cả = xoá mọi dòng của user; job dọn phiên quá hạn
CREATE TABLE login_session (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES user_account,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_session_user_idx    ON login_session (user_id);
CREATE INDEX login_session_expires_idx ON login_session (expires_at);

-- Mỗi lần gửi OTP (email hoặc SMS) = một dòng, kể cả gửi lại và gửi lỗi; không ghi đè.
-- Mã hiệu lực = dòng mới nhất của (user_id, purpose), chưa dùng, chưa hết hạn.
-- Giới hạn tần suất = đếm dòng trong cửa sổ thời gian (theo user / máy / số nhận), không phụ thuộc Redis.
-- Job dọn các dòng quá cửa sổ đếm.
CREATE TABLE otp_verification (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid        NOT NULL REFERENCES user_account,
    purpose     otp_purpose NOT NULL,
    phone       text        CHECK (phone ~ '^\+[1-9][0-9]{8,14}$'),   -- chỉ PHONE: số đang xác minh
    client_key  uuid        NOT NULL,                                -- khoá ẩn danh suy từ IP
    code_hash   text        NOT NULL,
    attempts    smallint    NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    sent_at     timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK ((purpose = 'PHONE') = (phone IS NOT NULL)),
    CHECK (expires_at > sent_at)
);
CREATE INDEX otp_verification_active_idx ON otp_verification (user_id, purpose, sent_at DESC);
CREATE INDEX otp_verification_client_idx ON otp_verification (client_key, sent_at DESC);
CREATE INDEX otp_verification_phone_idx  ON otp_verification (phone, sent_at DESC) WHERE phone IS NOT NULL;

CREATE TABLE oauth_state (
    state_hash   text        PRIMARY KEY,
    browser_hash text        NOT NULL,
    request      text        NOT NULL,      -- OAuth2AuthorizationRequest dạng JSON, đọc/ghi nguyên khối
    expires_at   timestamptz NOT NULL
);
CREATE INDEX oauth_state_expiry_idx ON oauth_state (expires_at);

-- ============================ CATALOG: SÂN & SƠ ĐỒ ============================
CREATE TABLE venue (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    name       text         NOT NULL,
    address    text,
    district   text,
    city       text,
    lat        numeric(9,6) CHECK (lat BETWEEN -90 AND 90),
    lng        numeric(9,6) CHECK (lng BETWEEN -180 AND 180),
    rules      text,                                         -- nội quy sân
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CHECK ((lat IS NULL) = (lng IS NULL))
);
CREATE INDEX venue_search_fts_idx ON venue
    USING gin (to_tsvector('simple', immutable_unaccent(coalesce(name, '') || ' ' || immutable_city_text(city))));

-- Sơ đồ nền & sơ đồ ghế: BẤT BIẾN. Nháp nằm ở venue_import_session / venue_import_file;
-- chỉ khi hoàn tất phiên nhập (đã kiểm hợp lệ) mới INSERT các bảng dưới. Không UPDATE/DELETE;
-- muốn đổi thì nhập version mới. Khu vực (AREA__*) và cổng (GATE__*) đọc từ chính SVG.

-- Nền SVG của sân (sân cỏ, sân khấu, khán đài, cổng)
CREATE TABLE venue_map (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    venue_id   uuid        NOT NULL REFERENCES venue,
    version    int         NOT NULL CHECK (version > 0),
    svg        text        NOT NULL CHECK (octet_length(svg) <= 10485760),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (venue_id, version),
    UNIQUE (id, venue_id)
);

-- Sơ đồ ghế vẽ trên một nền. Một sân có nhiều sơ đồ (bóng đá, concert sân khấu giữa…)
CREATE TABLE seat_map (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    venue_id     uuid        NOT NULL,
    venue_map_id uuid        NOT NULL,
    version      int         NOT NULL CHECK (version > 0),
    name         text        NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    layout_type  text        NOT NULL CHECK (layout_type IN ('FOOTBALL', 'CONCERT')),
    svg          text        NOT NULL CHECK (octet_length(svg) <= 10485760),
    created_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_map_id, venue_id) REFERENCES venue_map (id, venue_id),
    UNIQUE (venue_id, version),
    UNIQUE (id, venue_id)
);
CREATE INDEX seat_map_selection_idx ON seat_map (venue_id, layout_type, version DESC);

CREATE TABLE zone (
    id          uuid      PRIMARY KEY DEFAULT uuidv7(),
    seat_map_id uuid      NOT NULL REFERENCES seat_map,
    code        text      NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9-]*$'),   -- khớp ZONE__<code>__<KIND>[__capacity] trong SVG
    name        text      NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    kind        zone_kind NOT NULL,
    capacity    int       CHECK (capacity > 0),                          -- chỉ zone STANDING
    UNIQUE (seat_map_id, code),
    UNIQUE (id, seat_map_id),
    UNIQUE (id, seat_map_id, kind),                                    -- cho FK từ seat / standing_inventory
    CHECK ((kind = 'STANDING') = (capacity IS NOT NULL))
);

-- Ghế trong zone SEATED. Hình nằm ở seat_map.svg (phần tử SEAT__<zone>__<row>__<number>)
CREATE TABLE seat (
    id          uuid     PRIMARY KEY DEFAULT uuidv7(),
    seat_map_id uuid     NOT NULL,
    zone_id     uuid     NOT NULL,
    row_label   text     NOT NULL CHECK (row_label ~ '^[A-Za-z0-9-]+$'),   -- khớp quy ước id SVG
    number      smallint NOT NULL CHECK (number > 0),
    zone_kind   zone_kind NOT NULL DEFAULT 'SEATED' CHECK (zone_kind = 'SEATED'),   -- ghế chỉ thuộc zone SEATED
    FOREIGN KEY (zone_id, seat_map_id, zone_kind) REFERENCES zone (id, seat_map_id, kind),
    UNIQUE (zone_id, row_label, number),
    UNIQUE (id, zone_id, seat_map_id)                                  -- cho FK từ seat_inventory
);

-- ============================ CATALOG: SỰ KIỆN, SKU, SUẤT ============================
CREATE TABLE event (
    id                    uuid         PRIMARY KEY DEFAULT uuidv7(),
    title                 text         NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 300),
    description           text,
    category              text         NOT NULL CHECK (category IN ('FOOTBALL', 'CONCERT')),  -- khớp seat_map.layout_type
    home_team             text,                                   -- 4 cột dưới chỉ dùng cho FOOTBALL
    away_team             text,
    league                text,
    round                 text,
    poster_mobile_url     text,
    poster_desktop_url    text,
    max_tickets_per_order smallint     NOT NULL DEFAULT 10 CHECK (max_tickets_per_order > 0),
    seat_map_id           uuid         REFERENCES seat_map,        -- sân = seat_map.venue_id
    status                event_status NOT NULL DEFAULT 'DRAFT',
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (id, seat_map_id),
    CHECK (status = 'DRAFT' OR seat_map_id IS NOT NULL),
    CHECK (category = 'FOOTBALL' OR num_nonnulls(home_team, away_team, league, round) = 0)
);
CREATE INDEX event_fts_idx ON event
    USING gin (to_tsvector('simple', immutable_unaccent(title || ' ' || coalesce(description, ''))));
CREATE INDEX event_title_trgm_idx ON event USING gin (immutable_unaccent(lower(title)) gin_trgm_ops);

-- Hạng vé của event: tên, màu tô zone trên sơ đồ. Giá nằm ở sku_price (có lịch sử)
CREATE TABLE sku (
    id       uuid   PRIMARY KEY DEFAULT uuidv7(),
    event_id uuid   NOT NULL REFERENCES event,
    name     text   NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    color    text   NOT NULL CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    UNIQUE (event_id, name),
    UNIQUE (id, event_id)
);

-- Lịch sử giá của hạng vé. Chỉ INSERT: đổi giá = thêm dòng; effective_from ở tương lai = hẹn giờ đổi giá.
-- Giá hiện tại = dòng có effective_from <= now() lớn nhất. Giá chốt lúc giữ chỗ (order line trỏ vào dòng này)
CREATE TABLE sku_price (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    sku_id         uuid        NOT NULL REFERENCES sku,
    price          bigint      NOT NULL CHECK (price >= 0),
    effective_from timestamptz NOT NULL,
    created_by     uuid        NOT NULL REFERENCES user_account,
    reason         text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sku_id, effective_from),
    UNIQUE (id, price)                                   -- cho FK từ dòng đơn: unit_price khớp giá phiên bản
);
CREATE INDEX sku_price_current_idx ON sku_price (sku_id, effective_from DESC);

-- Zone nào mở bán trong event, thuộc SKU nào. Mỗi zone đúng 1 SKU; không có dòng = không bán
CREATE TABLE event_zone (
    event_id     uuid NOT NULL,
    zone_id      uuid NOT NULL,
    seat_map_id  uuid NOT NULL,
    sku_id       uuid NOT NULL,
    display_name text CHECK (length(btrim(display_name)) BETWEEN 1 AND 200),   -- NULL = dùng zone.name
    PRIMARY KEY (event_id, zone_id),
    FOREIGN KEY (sku_id, event_id)      REFERENCES sku (id, event_id),
    FOREIGN KEY (event_id, seat_map_id) REFERENCES event (id, seat_map_id),
    FOREIGN KEY (zone_id, seat_map_id)  REFERENCES zone (id, seat_map_id)
);
CREATE INDEX event_zone_sku_idx ON event_zone (sku_id);

-- Không lưu trạng thái: DRAFT theo event; CANCELLED = cancelled_at hoặc event huỷ;
-- SCHEDULED / ON_SALE / SALES_CLOSED / FINISHED tính khi đọc từ các mốc thời gian
CREATE TABLE showtime (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    event_id       uuid        NOT NULL,
    seat_map_id    uuid        NOT NULL,
    starts_at      timestamptz NOT NULL,
    ends_at        timestamptz NOT NULL,
    sale_opens_at  timestamptz NOT NULL,
    sale_closes_at timestamptz NOT NULL,
    cancelled_at   timestamptz,                -- NULL = suất còn hiệu lực
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (event_id, seat_map_id) REFERENCES event (id, seat_map_id),
    UNIQUE (id, seat_map_id),
    CHECK (starts_at < ends_at),
    CHECK (sale_opens_at < sale_closes_at AND sale_closes_at <= ends_at)
);
CREATE INDEX showtime_event_idx ON showtime (event_id, starts_at);

-- ============================ BOOKING ============================
CREATE TABLE ticket_order (
    id            uuid         PRIMARY KEY DEFAULT uuidv7(),
    code          char(6)      NOT NULL UNIQUE,
    user_id       uuid         NOT NULL REFERENCES user_account,
    showtime_id   uuid         NOT NULL REFERENCES showtime,
    status        order_status NOT NULL DEFAULT 'PENDING',
    total_amount  bigint       NOT NULL CHECK (total_amount >= 0),
    expires_at    timestamptz  NOT NULL,                  -- hạn giữ chỗ = tạo đơn + 10 phút
    buyer_name    text         CHECK (buyer_name = btrim(buyer_name) AND length(buyer_name) BETWEEN 1 AND 120),
    buyer_email   text         CHECK (buyer_email = lower(btrim(buyer_email)) AND length(buyer_email) <= 254),
    buyer_phone   text         CHECK (buyer_phone ~ '^\+[1-9][0-9]{8,14}$'),
    paid_at       timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (id, showtime_id),
    CHECK (num_nonnulls(buyer_name, buyer_email, buyer_phone) IN (0, 3)),
    CHECK (status <> 'PAID' OR (paid_at IS NOT NULL AND buyer_email IS NOT NULL))
);
CREATE UNIQUE INDEX ticket_order_one_pending_uk ON ticket_order (user_id, showtime_id) WHERE status = 'PENDING';
CREATE INDEX ticket_order_expiry_idx   ON ticket_order (expires_at) WHERE status = 'PENDING';
CREATE INDEX ticket_order_user_idx     ON ticket_order (user_id, created_at DESC);
CREATE INDEX ticket_order_showtime_idx ON ticket_order (showtime_id, status);
CREATE INDEX ticket_order_buyer_email_idx ON ticket_order (buyer_email) WHERE buyer_email IS NOT NULL;

-- Ghế theo suất. Trống = AVAILABLE hoặc (HELD và held_until < now()). Sinh khi mở bán suất
CREATE TABLE seat_inventory (
    showtime_id uuid        NOT NULL,
    seat_id     uuid        NOT NULL,
    zone_id     uuid        NOT NULL,
    seat_map_id uuid        NOT NULL,
    status      seat_status NOT NULL DEFAULT 'AVAILABLE',
    order_id    uuid        REFERENCES ticket_order,
    held_until  timestamptz,
    PRIMARY KEY (showtime_id, seat_id),
    FOREIGN KEY (showtime_id, seat_map_id) REFERENCES showtime (id, seat_map_id),
    FOREIGN KEY (seat_id, zone_id, seat_map_id) REFERENCES seat (id, zone_id, seat_map_id),
    CHECK ((status IN ('HELD', 'SOLD')) = (order_id IS NOT NULL)),
    CHECK ((status = 'HELD') = (held_until IS NOT NULL))
);
CREATE INDEX seat_inventory_zone_idx  ON seat_inventory (showtime_id, zone_id);
CREATE INDEX seat_inventory_order_idx ON seat_inventory (order_id) WHERE order_id IS NOT NULL;

-- Bộ đếm khu đứng theo suất (chỉ zone STANDING)
CREATE TABLE standing_inventory (
    showtime_id uuid NOT NULL,
    zone_id     uuid NOT NULL,
    seat_map_id uuid NOT NULL,
    capacity    int  NOT NULL CHECK (capacity >= 0),
    sold        int  NOT NULL DEFAULT 0 CHECK (sold >= 0),
    held        int  NOT NULL DEFAULT 0 CHECK (held >= 0),
    zone_kind   zone_kind NOT NULL DEFAULT 'STANDING' CHECK (zone_kind = 'STANDING'),  -- chỉ zone STANDING
    PRIMARY KEY (showtime_id, zone_id),
    FOREIGN KEY (showtime_id, seat_map_id) REFERENCES showtime (id, seat_map_id),
    FOREIGN KEY (zone_id, seat_map_id, zone_kind) REFERENCES zone (id, seat_map_id, kind),
    CHECK (sold + held <= capacity)
);

-- Dòng đơn khu ngồi: 1 dòng = 1 ghế = 1 vé. Zone lấy qua seat; tên hạng/zone đọc từ catalog (bị khoá sau mở bán)
CREATE TABLE order_seat (
    order_id     uuid   NOT NULL,
    showtime_id  uuid   NOT NULL,
    seat_id      uuid   NOT NULL,
    sku_price_id uuid   NOT NULL,                         -- phiên bản giá lúc giữ chỗ (→ hạng vé)
    unit_price   bigint NOT NULL CHECK (unit_price >= 0), -- bản ghi tiền
    PRIMARY KEY (order_id, seat_id),
    FOREIGN KEY (order_id, showtime_id)      REFERENCES ticket_order (id, showtime_id),
    FOREIGN KEY (showtime_id, seat_id)       REFERENCES seat_inventory (showtime_id, seat_id),
    FOREIGN KEY (sku_price_id, unit_price)   REFERENCES sku_price (id, price)
);

-- Dòng đơn khu đứng: 1 dòng = 1 zone + số lượng
CREATE TABLE order_standing (
    order_id     uuid     NOT NULL,
    showtime_id  uuid     NOT NULL,
    zone_id      uuid     NOT NULL,                       -- khu đứng không có ghế để suy ra zone
    sku_price_id uuid     NOT NULL,
    quantity     smallint NOT NULL CHECK (quantity > 0),
    unit_price   bigint   NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, zone_id),
    FOREIGN KEY (order_id, showtime_id)    REFERENCES ticket_order (id, showtime_id),
    FOREIGN KEY (showtime_id, zone_id)     REFERENCES standing_inventory (showtime_id, zone_id),
    FOREIGN KEY (sku_price_id, unit_price) REFERENCES sku_price (id, price)
);

-- Timeline đơn: mọi lần đổi trạng thái, do người hoặc hệ thống
CREATE TABLE order_status_history (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    order_id    uuid         NOT NULL REFERENCES ticket_order,
    from_status order_status,                             -- NULL = lúc tạo đơn
    to_status   order_status NOT NULL,
    actor_id    uuid         REFERENCES user_account,     -- NULL = hệ thống (IPN, job)
    reason      text,
    at          timestamptz  NOT NULL DEFAULT now(),
    CHECK (to_status NOT IN ('NEEDS_REVIEW', 'REJECTED') OR reason IS NOT NULL)
);
CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, at);

-- ============================ PAYMENT ============================
-- 1 lần thanh toán VNPay của đơn. Số tiền và hạn lấy từ ticket_order; kết quả chi tiết ở payment_gateway_log.
-- Chỉ IPN hoặc querydr chuyển sang SUCCEEDED, cùng transaction ghi dòng SUCCESS vào log
CREATE TABLE payment (
    id          uuid           PRIMARY KEY DEFAULT uuidv7(),
    order_id    uuid           NOT NULL REFERENCES ticket_order,
    txn_ref     text           NOT NULL UNIQUE,         -- vnp_TxnRef
    status      payment_status NOT NULL DEFAULT 'OPEN',
    payment_url text           NOT NULL,                -- trả lại khi khách bấm thanh toán lần nữa
    created_at  timestamptz    NOT NULL DEFAULT now(),  -- = vnp_CreateDate
    updated_at  timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX payment_one_open_uk ON payment (order_id) WHERE status = 'OPEN';
CREATE INDEX payment_order_idx ON payment (order_id);
CREATE INDEX payment_open_idx  ON payment (created_at) WHERE status = 'OPEN';   -- job reconcile

-- Mọi IPN / querydr nhận về, giữ raw để đối soát. Chỉ INSERT, không sửa, không xoá
CREATE TABLE payment_gateway_log (
    id             uuid           PRIMARY KEY DEFAULT uuidv7(),
    payment_id     uuid           NOT NULL REFERENCES payment,
    source         payment_source NOT NULL,
    result         payment_result NOT NULL,
    response_code  text,
    raw            jsonb          NOT NULL,                  -- tham số VNPay gửi về, đã bỏ chữ ký
    gateway_txn_no text GENERATED ALWAYS AS (raw ->> 'vnp_TransactionNo') STORED,
    received_at    timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX payment_gateway_log_payment_idx ON payment_gateway_log (payment_id, received_at DESC);
CREATE UNIQUE INDEX payment_gateway_log_one_success_uk ON payment_gateway_log (payment_id) WHERE result = 'SUCCESS';
CREATE INDEX payment_gateway_log_txn_no_idx ON payment_gateway_log (gateway_txn_no) WHERE gateway_txn_no IS NOT NULL;

-- ============================ TICKETING ============================
-- Vé, phát trong transaction IPN. Ngồi: seat_id (zone lấy qua seat); đứng: zone_id + seq (1..quantity).
-- QR = VG1.<id>.<qr_version>.<HMAC(QR_SECRET)>, tính khi cần, không lưu; cấp lại QR thì tăng qr_version.
-- Hạng vé lấy qua dòng đơn → sku_price → sku. Lý do/thời điểm huỷ vé ghi ở audit_log (VOID_TICKET).
CREATE TABLE ticket (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    order_id    uuid          NOT NULL,
    showtime_id uuid          NOT NULL,
    seat_id     uuid,                                     -- vé ngồi
    zone_id     uuid,                                     -- vé đứng
    seq         smallint      CHECK (seq > 0),            -- vé đứng thứ mấy
    code        text          NOT NULL UNIQUE,
    qr_version  smallint      NOT NULL DEFAULT 1 CHECK (qr_version > 0),
    status      ticket_status NOT NULL DEFAULT 'ISSUED',
    used_at     timestamptz,
    FOREIGN KEY (order_id, showtime_id) REFERENCES ticket_order (id, showtime_id),
    FOREIGN KEY (order_id, seat_id)     REFERENCES order_seat (order_id, seat_id),
    FOREIGN KEY (order_id, zone_id)     REFERENCES order_standing (order_id, zone_id),
    CHECK ((seat_id IS NULL) = (zone_id IS NOT NULL)),
    CHECK ((zone_id IS NULL) = (seq IS NULL)),
    CHECK ((status = 'USED') = (used_at IS NOT NULL))
);
CREATE UNIQUE INDEX ticket_seat_active_uk ON ticket (showtime_id, seat_id)
    WHERE seat_id IS NOT NULL AND status <> 'VOID';             -- chống bán trùng ghế giữa các đơn
CREATE UNIQUE INDEX ticket_standing_seq_uk ON ticket (order_id, zone_id, seq)
    WHERE zone_id IS NOT NULL;                                   -- phát vé đứng idempotent
CREATE INDEX ticket_order_idx    ON ticket (order_id);
CREATE INDEX ticket_showtime_idx ON ticket (showtime_id, status);

-- Mọi lần quét QR, kể cả bị từ chối
CREATE TABLE ticket_scan (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    ticket_id   uuid        REFERENCES ticket,                 -- NULL khi QR sai chữ ký
    showtime_id uuid        NOT NULL REFERENCES showtime,      -- suất đang soát
    gate_code   text,                                          -- cổng soát: GATE__<code> trong SVG nền hoặc nhập tay
    scanned_by  uuid        NOT NULL REFERENCES user_account,
    result      scan_result NOT NULL,
    scanned_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (ticket_id IS NOT NULL OR result = 'INVALID_QR')
);
CREATE INDEX ticket_scan_ticket_idx   ON ticket_scan (ticket_id);
CREATE INDEX ticket_scan_showtime_idx ON ticket_scan (showtime_id, scanned_at DESC);
CREATE UNIQUE INDEX ticket_scan_one_ok_uk ON ticket_scan (ticket_id) WHERE result = 'OK';   -- mỗi vé vào cổng 1 lần

-- ============================ HẠ TẦNG ============================
CREATE TABLE idempotency_record (
    user_id         uuid        NOT NULL REFERENCES user_account,
    endpoint        text        NOT NULL,
    key             text        NOT NULL,
    request_hash    text        NOT NULL,
    response_status smallint,
    response        jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, endpoint, key)
);
CREATE INDEX idempotency_record_created_idx ON idempotency_record (created_at);

-- Thao tác admin. before/after chỉ chứa trường thay đổi, đọc từ dòng đã khoá FOR UPDATE
-- trong cùng transaction (không viết cứng). Khoá/mở ghế: entity = showtime, after = danh sách ghế
CREATE TABLE audit_log (
    id        uuid        PRIMARY KEY DEFAULT uuidv7(),
    actor_id  uuid        NOT NULL REFERENCES user_account,
    action    text        NOT NULL CHECK (action IN (
                  'VOID_TICKET', 'REISSUE_QR', 'RESEND_TICKET', 'RESOLVE_REVIEW',
                  'BLOCK_SEATS', 'UNBLOCK_SEATS', 'CANCEL_EVENT', 'CANCEL_SHOWTIME', 'UPDATE_ACCOUNT')),
    entity    text        NOT NULL CHECK (entity IN (   -- = tên bảng của đối tượng
                  'ticket_order', 'ticket', 'event', 'showtime', 'user_account')),
    entity_id uuid        NOT NULL,
    reason    text,
    before    jsonb,
    after     jsonb,
    via_agent boolean     NOT NULL DEFAULT false,
    at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity, entity_id);
CREATE INDEX audit_log_at_idx     ON audit_log (at DESC);

CREATE TABLE notification_outbox (
    id         uuid            PRIMARY KEY DEFAULT uuidv7(),
    channel    contact_channel NOT NULL,
    recipient  text            NOT NULL,
    template   text            NOT NULL,
    payload    jsonb           NOT NULL DEFAULT '{}',
    status     outbox_status   NOT NULL DEFAULT 'PENDING',
    attempts   smallint        NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    send_after timestamptz     NOT NULL DEFAULT now(),
    last_error text,
    sent_at    timestamptz,
    created_at timestamptz     NOT NULL DEFAULT now()
);
CREATE INDEX notification_outbox_pending_idx ON notification_outbox (send_after) WHERE status = 'PENDING';

CREATE TABLE realtime_event (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES user_account,
    type       text        NOT NULL,                           -- order.paid, chat.delta... (danh sách ở code)
    payload    jsonb       NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX realtime_event_user_idx    ON realtime_event (user_id, id);
CREATE INDEX realtime_event_created_idx ON realtime_event (created_at);

CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);

-- ============================ NHẬP SƠ ĐỒ (wizard admin) ============================
-- Phiên còn tồn tại = đang nháp. Hoàn tất (tạo venue_map/seat_map/zone/seat) hoặc huỷ đều XOÁ phiên;
-- ai nhập sơ đồ nào ghi ở audit_log. Job dọn phiên bỏ dở: updated_at < now() - 30 ngày.
CREATE TABLE venue_import_session (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    owner_id   uuid        NOT NULL REFERENCES user_account,
    venue_id   uuid        REFERENCES venue,                  -- NULL = tạo sân mới
    draft      jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(draft) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()             -- optimistic lock + mốc dọn
);
CREATE INDEX venue_import_session_owner_idx   ON venue_import_session (owner_id, updated_at DESC);
CREATE INDEX venue_import_session_updated_idx ON venue_import_session (updated_at);

-- SVG đã làm sạch ngay lúc tải lên; không nhận diện được vai trò thì từ chối, không lưu
CREATE TABLE venue_import_file (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    session_id uuid NOT NULL REFERENCES venue_import_session ON DELETE CASCADE,
    filename   text NOT NULL CHECK (length(filename) BETWEEN 1 AND 255),
    role       text NOT NULL CHECK (role IN ('OVERVIEW', 'SEAT_LAYOUT')),
    svg        text NOT NULL CHECK (octet_length(svg) BETWEEN 1 AND 10485760)
);
CREATE INDEX venue_import_file_session_idx ON venue_import_file (session_id);
CREATE UNIQUE INDEX venue_import_file_one_overview_uk ON venue_import_file (session_id) WHERE role = 'OVERVIEW';

-- Giải thích bảng và cột cho schema v2. Chạy sau schema-v2.sql; tbls đọc các COMMENT này để sinh tài liệu.
-- Khi chuyển thành migration Flyway, gộp các COMMENT vào cùng file tạo bảng.

-- ============================ AUTH ============================
COMMENT ON TABLE user_account IS 'Tài khoản đăng nhập (BUYER hoặc ADMIN). Không chứa người mua của đơn.';
COMMENT ON COLUMN user_account.id IS 'Khoá chính uuidv7';
COMMENT ON COLUMN user_account.email IS 'Email đăng nhập, chữ thường, unique; điền sẵn email nhận vé';
COMMENT ON COLUMN user_account.phone IS 'SĐT E.164 (+84…), tuỳ chọn; unique chỉ khi đã xác minh; điền sẵn SĐT người mua';
COMMENT ON COLUMN user_account.full_name IS 'Họ tên 1–120 ký tự; điền sẵn tên người mua';
COMMENT ON COLUMN user_account.avatar_url IS 'Ảnh đại diện https lấy từ Google; web sẽ hiển thị sau';
COMMENT ON COLUMN user_account.password_hash IS 'Argon2; NULL nếu chỉ đăng nhập Google';
COMMENT ON COLUMN user_account.google_sub IS 'Subject Google OIDC, unique; NULL nếu chỉ dùng mật khẩu';
COMMENT ON COLUMN user_account.role IS 'BUYER | ADMIN — quyết định endpoint được gọi';
COMMENT ON COLUMN user_account.email_verified_at IS 'Thời điểm xác minh email (OTP hoặc Google); NULL = chưa xác minh';
COMMENT ON COLUMN user_account.phone_verified_at IS 'Thời điểm xác minh SĐT bằng SMS OTP; bắt buộc trước khi giữ ghế; đổi số thì về NULL';
COMMENT ON COLUMN user_account.admin_locked IS 'Admin khoá: chặn mọi đăng nhập, JWT và refresh';
COMMENT ON COLUMN user_account.auth_version IS 'Nhúng vào JWT; tăng khi đổi quyền/khoá/đặt lại mật khẩu để vô hiệu JWT cũ';
COMMENT ON COLUMN user_account.failed_login_count IS 'Số lần sai mật khẩu liên tiếp; về 0 khi đăng nhập đúng';
COMMENT ON COLUMN user_account.last_failed_login_at IS 'Lần sai gần nhất; quá cửa sổ đếm thì đếm lại từ 1';
COMMENT ON COLUMN user_account.locked_until IS 'Khoá tạm do sai mật khẩu quá số lần (ACCOUNT_LOCKED) khi > now()';
COMMENT ON COLUMN user_account.deleted_at IS 'Xoá tài khoản; khác NULL thì không đăng nhập được, dữ liệu cá nhân được ẩn danh hoá';
COMMENT ON COLUMN user_account.created_at IS 'Thời điểm tạo';
COMMENT ON COLUMN user_account.updated_at IS 'Lần sửa gần nhất';

COMMENT ON TABLE login_session IS 'Phiên đăng nhập, mỗi thiết bị một dòng; refresh token xoay vòng tại chỗ, chỉ lưu hash. Đăng xuất = xoá dòng.';
COMMENT ON COLUMN login_session.id IS 'Mã phiên; "đăng xuất thiết bị này" xoá theo mã này';
COMMENT ON COLUMN login_session.user_id IS 'Chủ phiên; "đăng xuất tất cả" xoá mọi phiên của user';
COMMENT ON COLUMN login_session.token_hash IS 'SHA-256 của refresh token hiện tại; mỗi lần làm mới thì ghi đè; không lưu bản rõ';
COMMENT ON COLUMN login_session.expires_at IS 'Hạn tuyệt đối của phiên; job dọn xoá phiên quá hạn';
COMMENT ON COLUMN login_session.created_at IS 'Thời điểm đăng nhập';

COMMENT ON TABLE otp_verification IS 'Mỗi lần gửi OTP = một dòng (kể cả gửi lại, gửi lỗi). Mã hiệu lực = dòng mới nhất của (user, mục đích); đếm dòng để giới hạn tần suất. Chỉ lưu hash.';
COMMENT ON COLUMN otp_verification.id IS 'Khoá chính; trả cho client làm mã phiên OTP';
COMMENT ON COLUMN otp_verification.user_id IS 'User đang xác minh';
COMMENT ON COLUMN otp_verification.purpose IS 'VERIFY (email tài khoản) | RESET (đặt lại mật khẩu) — gửi email; PHONE (xác minh SĐT) — gửi SMS';
COMMENT ON COLUMN otp_verification.phone IS 'Chỉ PHONE: số đang xác minh; thành công thì ghi vào user_account.phone';
COMMENT ON COLUMN otp_verification.client_key IS 'Khoá ẩn danh suy từ IP (không lưu IP thật); đếm số lần gửi theo máy';
COMMENT ON COLUMN otp_verification.code_hash IS 'Hash của mã OTP';
COMMENT ON COLUMN otp_verification.attempts IS 'Số lần nhập sai mã của dòng này; vượt ngưỡng → OTP_TOO_MANY_ATTEMPTS';
COMMENT ON COLUMN otp_verification.sent_at IS 'Thời điểm gửi; chặn gửi lại quá nhanh và làm mốc đếm tần suất';
COMMENT ON COLUMN otp_verification.expires_at IS 'Hết hạn mã → OTP_EXPIRED';
COMMENT ON COLUMN otp_verification.consumed_at IS 'Xác minh thành công; dòng vẫn giữ để đếm tần suất đến khi job dọn';

COMMENT ON TABLE oauth_state IS 'State tạm của luồng đăng nhập Google (chống CSRF), xoá khi hết hạn.';
COMMENT ON COLUMN oauth_state.state_hash IS 'Hash của tham số state gửi sang Google';
COMMENT ON COLUMN oauth_state.browser_hash IS 'Hash cookie trình duyệt; bảo đảm callback về đúng trình duyệt đã bắt đầu';
COMMENT ON COLUMN oauth_state.request IS 'OAuth2AuthorizationRequest dạng JSON (PKCE verifier, nonce, redirect); đọc/ghi nguyên khối nên để text';
COMMENT ON COLUMN oauth_state.expires_at IS 'Hết hạn (vài phút)';

-- ============================ CATALOG: SÂN & SƠ ĐỒ ============================
COMMENT ON TABLE venue IS 'Sân / địa điểm tổ chức.';
COMMENT ON COLUMN venue.id IS 'Khoá chính';
COMMENT ON COLUMN venue.name IS 'Tên sân; dùng cho tìm kiếm full-text';
COMMENT ON COLUMN venue.address IS 'Địa chỉ đường';
COMMENT ON COLUMN venue.district IS 'Quận/huyện, để hiển thị';
COMMENT ON COLUMN venue.city IS 'Thành phố; lọc và tìm "sự kiện ở Hà Nội"';
COMMENT ON COLUMN venue.lat IS 'Vĩ độ; cùng có hoặc cùng NULL với lng';
COMMENT ON COLUMN venue.lng IS 'Kinh độ; dùng mở bản đồ/chỉ đường';
COMMENT ON COLUMN venue.rules IS 'Nội quy sân: đồ cấm mang vào, quy định khán giả; hiển thị cho khách và AI agent trả lời';
COMMENT ON COLUMN venue.created_at IS 'Thời điểm tạo';
COMMENT ON COLUMN venue.updated_at IS 'Lần sửa gần nhất';
COMMENT ON COLUMN venue.deleted_at IS 'Xoá mềm; sân đã có event không xoá cứng được';

COMMENT ON TABLE venue_map IS 'Nền SVG của sân, bất biến. Chỉ tạo khi hoàn tất phiên nhập; sửa = nhập version mới.';
COMMENT ON COLUMN venue_map.id IS 'Khoá chính';
COMMENT ON COLUMN venue_map.venue_id IS 'Thuộc sân nào';
COMMENT ON COLUMN venue_map.version IS 'Phiên bản trong sân (max + 1); bản cũ giữ nguyên cho sơ đồ ghế và event cũ';
COMMENT ON COLUMN venue_map.svg IS 'SVG nền đã làm sạch, ≤ 10 MB; chứa viewBox, khu vực AREA__* và cổng GATE__*';
COMMENT ON COLUMN venue_map.created_at IS 'Thời điểm hoàn tất phiên nhập';

COMMENT ON TABLE seat_map IS 'Sơ đồ ghế, bất biến. Chỉ tạo khi hoàn tất phiên nhập (đã kiểm hợp lệ); tồn tại = dùng được.';
COMMENT ON COLUMN seat_map.id IS 'Khoá chính';
COMMENT ON COLUMN seat_map.venue_id IS 'Thuộc sân nào; cùng venue_map_id tạo FK ghép chặn dùng nền của sân khác';
COMMENT ON COLUMN seat_map.venue_map_id IS 'Vẽ trên nền nào';
COMMENT ON COLUMN seat_map.version IS 'Phiên bản trong sân (max + 1)';
COMMENT ON COLUMN seat_map.name IS 'Tên cho admin chọn khi tạo event (VD "Mỹ Đình – concert sân khấu giữa")';
COMMENT ON COLUMN seat_map.layout_type IS 'FOOTBALL | CONCERT — cách bố trí; event chỉ chọn sơ đồ đúng loại';
COMMENT ON COLUMN seat_map.svg IS 'SVG ghế và zone đã làm sạch, ≤ 10 MB';
COMMENT ON COLUMN seat_map.created_at IS 'Thời điểm hoàn tất phiên nhập';

COMMENT ON TABLE zone IS 'Khu trên sơ đồ ghế, bất biến. Chỉ lưu dữ liệu nghiệp vụ; hình (svg id, khung bao) đọc từ seat_map.svg.';
COMMENT ON COLUMN zone.id IS 'Khoá chính';
COMMENT ON COLUMN zone.seat_map_id IS 'Thuộc sơ đồ ghế nào';
COMMENT ON COLUMN zone.code IS 'Mã ổn định (A1, FAN…), unique trong sơ đồ; id phần tử SVG = ZONE__<code>__<KIND>[__capacity], web khớp hình với zone qua mã này';
COMMENT ON COLUMN zone.name IS 'Tên hiển thị mặc định, đặt khi nhập sơ đồ, dùng chung mọi event; event đổi được qua event_zone.display_name';
COMMENT ON COLUMN zone.kind IS 'SEATED (có ghế, tồn kho seat_inventory) | STANDING (bộ đếm standing_inventory)';
COMMENT ON COLUMN zone.capacity IS 'Sức chứa, chỉ zone STANDING; zone ngồi = số ghế, không lưu lặp';

COMMENT ON TABLE seat IS 'Ghế trong zone SEATED. Hình (toạ độ) nằm ở seat_map.svg, phần tử SEAT__<zone>__<row>__<number>; trạng thái theo suất ở seat_inventory.';
COMMENT ON COLUMN seat.id IS 'Khoá chính';
COMMENT ON COLUMN seat.seat_map_id IS 'Chép từ zone; cho FK ghép từ seat_inventory bảo đảm ghế thuộc sơ đồ của suất';
COMMENT ON COLUMN seat.zone_id IS 'Thuộc khu nào';
COMMENT ON COLUMN seat.row_label IS 'Hàng (F); in trên vé; cùng number dựng id SVG để web tìm chấm ghế';
COMMENT ON COLUMN seat.number IS 'Số ghế trong hàng (12); unique theo (zone, hàng, số)';
COMMENT ON COLUMN seat.zone_kind IS 'Luôn SEATED; cùng FK sang zone(id, seat_map_id, kind) chặn ghế thuộc zone STANDING';

-- ============================ CATALOG: SỰ KIỆN, SKU, SUẤT ============================
COMMENT ON TABLE event IS 'Sự kiện. Dùng một sơ đồ ghế (sân lấy từ seat_map), có nhiều suất.';
COMMENT ON COLUMN event.id IS 'Khoá chính';
COMMENT ON COLUMN event.title IS 'Tên sự kiện; tìm kiếm full-text và gần đúng';
COMMENT ON COLUMN event.description IS 'Mô tả chi tiết';
COMMENT ON COLUMN event.category IS 'FOOTBALL | CONCERT — lọc trang chủ, quyết định form nhập; phải khớp seat_map.layout_type';
COMMENT ON COLUMN event.home_team IS 'Đội nhà (FOOTBALL); banner hiện "<home> vs <away>"';
COMMENT ON COLUMN event.away_team IS 'Đội khách (FOOTBALL)';
COMMENT ON COLUMN event.league IS 'Giải đấu (FOOTBALL), dòng phụ banner';
COMMENT ON COLUMN event.round IS 'Vòng đấu (FOOTBALL), dòng phụ banner';
COMMENT ON COLUMN event.poster_mobile_url IS 'Poster tỉ lệ dọc cho mobile';
COMMENT ON COLUMN event.poster_desktop_url IS 'Poster tỉ lệ ngang cho desktop';
COMMENT ON COLUMN event.max_tickets_per_order IS 'Tối đa vé mỗi đơn → MAX_TICKETS_EXCEEDED; chống gom vé';
COMMENT ON COLUMN event.seat_map_id IS 'Sơ đồ ghế dùng cho mọi suất; sân = seat_map.venue_id; bắt buộc khi không còn DRAFT';
COMMENT ON COLUMN event.status IS 'DRAFT | PUBLISHED | CANCELLED; "đã kết thúc" tính khi đọc từ suất cuối';
COMMENT ON COLUMN event.created_at IS 'Thời điểm tạo';
COMMENT ON COLUMN event.updated_at IS 'Lần sửa gần nhất';

COMMENT ON TABLE sku IS 'Hạng vé của event (tên, màu). Gom nhiều zone qua event_zone; giá ở sku_price. Tên và gán zone bị khoá sau mở bán.';
COMMENT ON COLUMN sku.id IS 'Khoá chính';
COMMENT ON COLUMN sku.event_id IS 'Thuộc event nào';
COMMENT ON COLUMN sku.name IS 'VIP, Khán đài A…; unique trong event, in trên vé';
COMMENT ON COLUMN sku.color IS 'Màu #RRGGBB tô các zone của hạng trên sơ đồ';

COMMENT ON TABLE sku_price IS 'Lịch sử giá hạng vé, chỉ INSERT. Giá hiện tại = dòng effective_from <= now() mới nhất; effective_from tương lai = hẹn giờ đổi giá.';
COMMENT ON COLUMN sku_price.id IS 'Khoá chính; dòng đơn trỏ vào để biết mua theo giá nào';
COMMENT ON COLUMN sku_price.sku_id IS 'Hạng vé';
COMMENT ON COLUMN sku_price.price IS 'Giá (VND)';
COMMENT ON COLUMN sku_price.effective_from IS 'Có hiệu lực từ thời điểm này (được đặt ở tương lai)';
COMMENT ON COLUMN sku_price.created_by IS 'Admin đổi giá';
COMMENT ON COLUMN sku_price.reason IS 'Lý do đổi giá (hết early bird, trận cầu nóng…)';
COMMENT ON COLUMN sku_price.created_at IS 'Thời điểm tạo bản giá';

COMMENT ON TABLE event_zone IS 'Zone nào mở bán trong event và thuộc SKU nào. Mỗi zone đúng 1 SKU; không có dòng = không bán.';
COMMENT ON COLUMN event_zone.event_id IS 'Event';
COMMENT ON COLUMN event_zone.zone_id IS 'Zone mở bán';
COMMENT ON COLUMN event_zone.seat_map_id IS 'Chép từ event; cầu nối FK ghép bảo đảm zone thuộc đúng sơ đồ event đã chọn';
COMMENT ON COLUMN event_zone.sku_id IS 'Hạng vé của zone → lấy giá và màu';
COMMENT ON COLUMN event_zone.display_name IS 'Tên zone riêng cho event; NULL = zone.name';

COMMENT ON TABLE showtime IS 'Suất diễn / trận. Không lưu trạng thái: DRAFT theo event, CANCELLED theo cancelled_at, còn lại tính từ các mốc thời gian.';
COMMENT ON COLUMN showtime.id IS 'Khoá chính';
COMMENT ON COLUMN showtime.event_id IS 'Thuộc event nào';
COMMENT ON COLUMN showtime.seat_map_id IS 'Chép từ event; FK ghép bảo đảm suất dùng đúng sơ đồ của event';
COMMENT ON COLUMN showtime.starts_at IS 'Giờ bắt đầu';
COMMENT ON COLUMN showtime.ends_at IS 'Giờ kết thúc; qua mốc này = FINISHED';
COMMENT ON COLUMN showtime.sale_opens_at IS 'Mở bán; trước mốc này giữ chỗ → SHOWTIME_NOT_ON_SALE';
COMMENT ON COLUMN showtime.sale_closes_at IS 'Đóng bán; ≤ ends_at';
COMMENT ON COLUMN showtime.cancelled_at IS 'Thời điểm admin huỷ suất; NULL = còn hiệu lực. Lý do ghi ở audit_log (CANCEL_SHOWTIME)';
COMMENT ON COLUMN showtime.created_at IS 'Thời điểm tạo';
COMMENT ON COLUMN showtime.updated_at IS 'Lần sửa gần nhất';

-- ============================ BOOKING ============================
COMMENT ON TABLE ticket_order IS 'Đơn mua vé của một suất. PENDING giữ chỗ 10 phút; chỉ IPN/querydr chuyển sang PAID.';
COMMENT ON COLUMN ticket_order.id IS 'Khoá chính';
COMMENT ON COLUMN ticket_order.code IS 'Mã đơn 6 ký tự cho khách tra cứu';
COMMENT ON COLUMN ticket_order.user_id IS 'Tài khoản đặt đơn — quyền sở hữu';
COMMENT ON COLUMN ticket_order.showtime_id IS 'Suất; mỗi user tối đa 1 đơn PENDING mỗi suất';
COMMENT ON COLUMN ticket_order.status IS 'PENDING | PAID | EXPIRED | CANCELLED | NEEDS_REVIEW | REJECTED';
COMMENT ON COLUMN ticket_order.total_amount IS 'Tổng tiền (VND) tính phía server; so với vnp_Amount/100 khi IPN';
COMMENT ON COLUMN ticket_order.expires_at IS 'Hạn giữ chỗ = tạo đơn + 10 phút; job expire_holds quét theo cột này';
COMMENT ON COLUMN ticket_order.buyer_name IS 'Người mua (có thể khác chủ tài khoản), ghi lúc tạo payment';
COMMENT ON COLUMN ticket_order.buyer_email IS 'Email nhận vé; bắt buộc khi PAID';
COMMENT ON COLUMN ticket_order.buyer_phone IS 'SĐT liên hệ E.164';
COMMENT ON COLUMN ticket_order.paid_at IS 'Thời điểm chốt bán; bắt buộc khi PAID';
COMMENT ON COLUMN ticket_order.created_at IS 'Thời điểm tạo đơn';
COMMENT ON COLUMN ticket_order.updated_at IS 'Lần đổi gần nhất';

COMMENT ON TABLE seat_inventory IS 'Ghế theo suất, sinh khi mở bán. Trống = AVAILABLE hoặc (HELD và held_until < now()).';
COMMENT ON COLUMN seat_inventory.showtime_id IS 'Suất';
COMMENT ON COLUMN seat_inventory.seat_id IS 'Ghế; khoá chính (suất, ghế) = mỗi ghế đúng một trạng thái';
COMMENT ON COLUMN seat_inventory.zone_id IS 'Chép từ seat; đếm ghế trống theo zone không cần join';
COMMENT ON COLUMN seat_inventory.seat_map_id IS 'Cùng FK (seat_id, zone_id, seat_map_id) bảo đảm ghế thuộc sơ đồ của suất và zone_id đúng';
COMMENT ON COLUMN seat_inventory.status IS 'AVAILABLE | HELD | SOLD | BLOCKED (admin khoá)';
COMMENT ON COLUMN seat_inventory.order_id IS 'Đơn đang giữ hoặc đã mua; bắt buộc khi HELD/SOLD';
COMMENT ON COLUMN seat_inventory.held_until IS 'Hạn giữ; qua hạn ghế tự coi là trống, không chờ job';

COMMENT ON TABLE standing_inventory IS 'Bộ đếm khu đứng theo suất. CHECK sold + held <= capacity chặn bán quá.';
COMMENT ON COLUMN standing_inventory.showtime_id IS 'Suất';
COMMENT ON COLUMN standing_inventory.zone_id IS 'Zone STANDING';
COMMENT ON COLUMN standing_inventory.seat_map_id IS 'FK ghép bảo đảm zone thuộc sơ đồ của suất';
COMMENT ON COLUMN standing_inventory.capacity IS 'Sức chứa của suất (chép từ zone, admin có thể giảm)';
COMMENT ON COLUMN standing_inventory.sold IS 'Đã bán (đơn PAID)';
COMMENT ON COLUMN standing_inventory.held IS 'Đang giữ (đơn PENDING); job expire_holds trả lại';
COMMENT ON COLUMN standing_inventory.zone_kind IS 'Luôn STANDING; cùng FK sang zone(id, seat_map_id, kind) chặn bộ đếm cho zone SEATED';

COMMENT ON TABLE order_seat IS 'Dòng đơn khu ngồi: 1 dòng = 1 ghế = 1 vé. Zone lấy qua seat; tên hạng/zone đọc từ catalog.';
COMMENT ON COLUMN order_seat.order_id IS 'Đơn';
COMMENT ON COLUMN order_seat.showtime_id IS 'Suất; FK ghép bảo đảm cùng suất với đơn';
COMMENT ON COLUMN order_seat.seat_id IS 'Ghế đã chọn';
COMMENT ON COLUMN order_seat.sku_price_id IS 'Phiên bản giá áp lúc giữ chỗ; suy ra hạng vé (sku)';
COMMENT ON COLUMN order_seat.unit_price IS 'Số tiền thật tính cho ghế (bản ghi tiền); FK bảo đảm bằng sku_price.price';

COMMENT ON TABLE order_standing IS 'Dòng đơn khu đứng: 1 dòng = 1 zone × quantity = quantity vé.';
COMMENT ON COLUMN order_standing.order_id IS 'Đơn';
COMMENT ON COLUMN order_standing.showtime_id IS 'Suất; FK ghép bảo đảm cùng suất với đơn';
COMMENT ON COLUMN order_standing.zone_id IS 'Zone STANDING; FK tới standing_inventory';
COMMENT ON COLUMN order_standing.quantity IS 'Số vé; = số held đã cộng, = số vé sẽ phát';
COMMENT ON COLUMN order_standing.sku_price_id IS 'Phiên bản giá áp lúc giữ chỗ; suy ra hạng vé (sku)';
COMMENT ON COLUMN order_standing.unit_price IS 'Giá mỗi vé (bản ghi tiền); FK bảo đảm bằng sku_price.price';

COMMENT ON TABLE order_status_history IS 'Timeline đơn: mọi lần đổi trạng thái, do người hoặc hệ thống.';
COMMENT ON COLUMN order_status_history.id IS 'Khoá chính';
COMMENT ON COLUMN order_status_history.order_id IS 'Đơn';
COMMENT ON COLUMN order_status_history.from_status IS 'Trạng thái trước; NULL = lúc tạo đơn';
COMMENT ON COLUMN order_status_history.to_status IS 'Trạng thái mới';
COMMENT ON COLUMN order_status_history.actor_id IS 'Admin thao tác; NULL = hệ thống (IPN, job)';
COMMENT ON COLUMN order_status_history.reason IS 'Lý do; bắt buộc khi chuyển sang NEEDS_REVIEW / REJECTED. Lý do hiện tại của đơn = dòng mới nhất';
COMMENT ON COLUMN order_status_history.at IS 'Thời điểm đổi';

-- ============================ PAYMENT ============================
COMMENT ON TABLE payment IS 'Một lần thanh toán VNPay của đơn. Số tiền và hạn lấy từ ticket_order; mã giao dịch, ngân hàng, thời điểm tiền về ở payment_gateway_log.';
COMMENT ON COLUMN payment.id IS 'Khoá chính';
COMMENT ON COLUMN payment.order_id IS 'Đơn; mỗi đơn tối đa 1 payment OPEN';
COMMENT ON COLUMN payment.txn_ref IS 'vnp_TxnRef gửi VNPay; IPN tra payment theo cột này';
COMMENT ON COLUMN payment.status IS 'OPEN | SUCCEEDED | FAILED | EXPIRED';
COMMENT ON COLUMN payment.payment_url IS 'URL VNPay đã ký; trả lại nguyên khi khách bấm thanh toán lần nữa trong lúc còn OPEN';
COMMENT ON COLUMN payment.created_at IS 'Thời điểm tạo = vnp_CreateDate; querydr cần ngày giao dịch gốc';
COMMENT ON COLUMN payment.updated_at IS 'Lần đổi gần nhất';

COMMENT ON TABLE payment_gateway_log IS 'Mọi IPN / querydr nhận về, giữ raw để đối soát. Không sửa, không xoá.';
COMMENT ON COLUMN payment_gateway_log.id IS 'Khoá chính';
COMMENT ON COLUMN payment_gateway_log.payment_id IS 'Payment tương ứng';
COMMENT ON COLUMN payment_gateway_log.source IS 'IPN (VNPay gọi) | QUERY (mình gọi querydr)';
COMMENT ON COLUMN payment_gateway_log.result IS 'SUCCESS | FAILED; mỗi payment tối đa 1 SUCCESS';
COMMENT ON COLUMN payment_gateway_log.response_code IS 'Mã kết quả: vnp_ResponseCode (IPN) hoặc vnp_TransactionStatus (querydr); bản mới nhất = lý do thất bại hiển thị';
COMMENT ON COLUMN payment_gateway_log.gateway_txn_no IS 'Tự sinh từ raw.vnp_TransactionNo; tra theo mã giao dịch trên sao kê VNPay';
COMMENT ON COLUMN payment_gateway_log.raw IS 'Toàn bộ tham số VNPay gửi về (đã bỏ chữ ký), gồm vnp_Amount, vnp_BankCode…; bằng chứng khi tranh chấp';
COMMENT ON COLUMN payment_gateway_log.received_at IS 'Thời điểm nhận';

-- ============================ TICKETING ============================
COMMENT ON TABLE ticket IS 'Vé, phát trong transaction IPN. Ngồi: seat_id; đứng: zone_id + seq. QR tính từ id + qr_version + HMAC, không lưu.';
COMMENT ON COLUMN ticket.id IS 'Khoá chính; nằm trong QR';
COMMENT ON COLUMN ticket.order_id IS 'Đơn sinh ra vé; cùng showtime_id tạo FK bảo đảm vé cùng suất với đơn';
COMMENT ON COLUMN ticket.showtime_id IS 'Suất; soát vé kiểm đúng suất';
COMMENT ON COLUMN ticket.zone_id IS 'Chỉ vé đứng: khu đứng (FK sang order_standing). Vé ngồi = NULL, zone lấy qua seat';
COMMENT ON COLUMN ticket.seat_id IS 'Ghế (vé ngồi); unique theo suất khi chưa VOID → chống bán trùng';
COMMENT ON COLUMN ticket.seq IS 'Vé thứ mấy trong dòng khu đứng; NULL với vé ngồi';
COMMENT ON COLUMN ticket.code IS 'Mã in trên vé, khách đọc cho nhân viên';
COMMENT ON COLUMN ticket.qr_version IS 'QR = VG1.<id>.<qr_version>.<HMAC(QR_SECRET)>; cấp lại QR thì tăng, QR cũ bị từ chối';
COMMENT ON COLUMN ticket.status IS 'ISSUED | USED | VOID (huỷ bởi admin, lý do ở audit_log)';
COMMENT ON COLUMN ticket.used_at IS 'Thời điểm qua cổng; bắt buộc khi USED; màn soát vé hiện ngay khi quét lại';

COMMENT ON TABLE ticket_scan IS 'Mọi lần quét QR ở cổng, kể cả bị từ chối.';
COMMENT ON COLUMN ticket_scan.id IS 'Khoá chính';
COMMENT ON COLUMN ticket_scan.ticket_id IS 'Vé quét được; NULL khi QR sai chữ ký';
COMMENT ON COLUMN ticket_scan.showtime_id IS 'Suất đang soát ở cổng';
COMMENT ON COLUMN ticket_scan.gate_code IS 'Cổng soát (GATE__<code> trong SVG nền hoặc nhập tay)';
COMMENT ON COLUMN ticket_scan.scanned_by IS 'Nhân viên soát vé';
COMMENT ON COLUMN ticket_scan.result IS 'OK | ALREADY_USED | TICKET_VOID | WRONG_SHOWTIME | INVALID_QR';
COMMENT ON COLUMN ticket_scan.scanned_at IS 'Thời điểm quét';

-- ============================ HẠ TẦNG ============================
COMMENT ON TABLE idempotency_record IS 'Kết quả request có Idempotency-Key (tạo hold, tạo payment, resolve-review). Giữ 24 giờ.';
COMMENT ON COLUMN idempotency_record.user_id IS 'Người gọi; key chỉ unique trong phạm vi user';
COMMENT ON COLUMN idempotency_record.endpoint IS 'Endpoint được gọi';
COMMENT ON COLUMN idempotency_record.key IS 'Giá trị header Idempotency-Key';
COMMENT ON COLUMN idempotency_record.request_hash IS 'Hash body; cùng key khác body → IDEMPOTENCY_CONFLICT';
COMMENT ON COLUMN idempotency_record.response_status IS 'HTTP status đã trả; NULL = đang xử lý';
COMMENT ON COLUMN idempotency_record.response IS 'Body đã trả, trả lại nguyên khi gọi trùng';
COMMENT ON COLUMN idempotency_record.created_at IS 'Thời điểm nhận; job dọn theo cột này';

COMMENT ON TABLE audit_log IS 'Thao tác admin (kể cả qua AI agent) kèm trạng thái trước/sau.';
COMMENT ON COLUMN audit_log.id IS 'Khoá chính';
COMMENT ON COLUMN audit_log.actor_id IS 'Admin thực hiện';
COMMENT ON COLUMN audit_log.action IS 'Loại thao tác; danh sách mở rộng theo tính năng';
COMMENT ON COLUMN audit_log.entity IS 'Tên bảng của đối tượng: ticket_order | ticket | event | showtime | user_account';
COMMENT ON COLUMN audit_log.entity_id IS 'id đối tượng';
COMMENT ON COLUMN audit_log.reason IS 'Lý do admin nhập';
COMMENT ON COLUMN audit_log.before IS 'Giá trị cũ của các trường bị thay đổi, đọc từ dòng đã khoá trong cùng transaction';
COMMENT ON COLUMN audit_log.after IS 'Giá trị mới của các trường bị thay đổi (khoá ghế: danh sách ghế)';
COMMENT ON COLUMN audit_log.via_agent IS 'true = thao tác qua chat AI agent';
COMMENT ON COLUMN audit_log.at IS 'Thời điểm';


COMMENT ON TABLE notification_outbox IS 'Email/SMS chờ gửi, ghi cùng transaction nghiệp vụ; job outbox_sender gửi.';
COMMENT ON COLUMN notification_outbox.id IS 'Khoá chính';
COMMENT ON COLUMN notification_outbox.channel IS 'EMAIL | SMS';
COMMENT ON COLUMN notification_outbox.recipient IS 'Địa chỉ nhận';
COMMENT ON COLUMN notification_outbox.template IS 'Mẫu tin (ticket_issued, otp…)';
COMMENT ON COLUMN notification_outbox.payload IS 'Biến điền vào mẫu';
COMMENT ON COLUMN notification_outbox.status IS 'PENDING | SENT | FAILED';
COMMENT ON COLUMN notification_outbox.attempts IS 'Số lần thử; quá ngưỡng → FAILED';
COMMENT ON COLUMN notification_outbox.send_after IS 'Gửi từ thời điểm này (retry lùi dần)';
COMMENT ON COLUMN notification_outbox.last_error IS 'Lỗi lần gửi gần nhất';
COMMENT ON COLUMN notification_outbox.sent_at IS 'Thời điểm gửi thành công';
COMMENT ON COLUMN notification_outbox.created_at IS 'Thời điểm ghi';

COMMENT ON TABLE realtime_event IS 'Sự kiện SSE gửi user; client nối lại thì phát lại từ id cuối. Giữ 1 ngày (realtime_gc).';
COMMENT ON COLUMN realtime_event.id IS 'Khoá chính uuidv7, dùng làm Last-Event-ID';
COMMENT ON COLUMN realtime_event.user_id IS 'Người nhận';
COMMENT ON COLUMN realtime_event.type IS 'order.paid, chat.delta…';
COMMENT ON COLUMN realtime_event.payload IS 'Dữ liệu sự kiện';
COMMENT ON COLUMN realtime_event.created_at IS 'Thời điểm tạo; job dọn theo cột này';

COMMENT ON TABLE shedlock IS 'Khoá job nền (ShedLock) để mỗi job chỉ chạy một nơi.';
COMMENT ON COLUMN shedlock.name IS 'Tên job';
COMMENT ON COLUMN shedlock.lock_until IS 'Khoá giữ tới';
COMMENT ON COLUMN shedlock.locked_at IS 'Thời điểm lấy khoá';
COMMENT ON COLUMN shedlock.locked_by IS 'Instance đang giữ khoá';

-- ============================ NHẬP SƠ ĐỒ ============================
COMMENT ON TABLE venue_import_session IS 'Nháp wizard nhập sân/sơ đồ. Tồn tại = đang nháp; hoàn tất hoặc huỷ thì xoá phiên.';
COMMENT ON COLUMN venue_import_session.id IS 'Khoá chính';
COMMENT ON COLUMN venue_import_session.owner_id IS 'Admin tạo phiên';
COMMENT ON COLUMN venue_import_session.venue_id IS 'Sân nhập version mới; NULL = tạo sân mới';
COMMENT ON COLUMN venue_import_session.draft IS 'Dữ liệu wizard: thông tin sân, tên sơ đồ, layout_type…';
COMMENT ON COLUMN venue_import_session.created_at IS 'Thời điểm tạo';
COMMENT ON COLUMN venue_import_session.updated_at IS 'Lần lưu gần nhất; lưu nháp kèm WHERE updated_at = giá trị cũ để chống ghi đè; job dọn phiên quá 30 ngày';

COMMENT ON TABLE venue_import_file IS 'File SVG tải lên trong phiên, đã làm sạch. Xoá theo phiên.';
COMMENT ON COLUMN venue_import_file.id IS 'Khoá chính';
COMMENT ON COLUMN venue_import_file.session_id IS 'Phiên; xoá phiên thì xoá file';
COMMENT ON COLUMN venue_import_file.filename IS 'Tên file gốc, hiển thị cho admin';
COMMENT ON COLUMN venue_import_file.role IS 'OVERVIEW (nền, tối đa 1 mỗi phiên) | SEAT_LAYOUT (sơ đồ ghế)';
COMMENT ON COLUMN venue_import_file.svg IS 'SVG đã làm sạch (bỏ script, link ngoài), ≤ 10 MB; kiểm hợp lệ tính lại khi cần';
