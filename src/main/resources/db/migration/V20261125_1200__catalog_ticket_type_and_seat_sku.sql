-- =============================================================================
-- Chuyển thiết kế SKU xuống cấp ghế (Seated) và khu (Standing) theo ticket-sku-redesign.md
-- Thực hiện 4 checklist đầu tiên trong mục "Schema và chuyển dữ liệu":
--   1. Tạo ticket_type, ticket_type_price và mở rộng sku (khóa ngoại ghép, kiểm tra
--      ghế hoặc khu đứng, index tra giá theo hạng và mốc hiệu lực).
--   2. Xác định hạng từ dữ liệu cũ, kiểm tra và chặn xung đột lịch sử giá nếu hai
--      SKU cũ trùng tên hạng dự kiến trước khi gộp.
--   3. Sinh SKU cho mỗi cặp (event, seat, ticket_type) đã cấu hình ở khu ngồi và
--      (event, zone, ticket_type) ở khu đứng. Đối chiếu số SKU, số dòng tồn và
--      giá có hiệu lực trước và sau chuyển dữ liệu.
--   4. Chuyển sang giá theo ticket_type_price, giữ đường đọc đơn cũ (sku_price_id)
--      và giữ nguyên unit_price đã chốt.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Tạo bảng ticket_type và ticket_type_price
-- -----------------------------------------------------------------------------
CREATE TABLE ticket_type (
    id       uuid PRIMARY KEY DEFAULT uuidv7(),
    event_id uuid NOT NULL REFERENCES event,
    name     text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    color    text NOT NULL CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    UNIQUE (event_id, name),
    UNIQUE (id, event_id)
);

CREATE TABLE ticket_type_price (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    ticket_type_id uuid        NOT NULL REFERENCES ticket_type,
    price          bigint      NOT NULL CHECK (price >= 0),
    effective_from timestamptz NOT NULL,
    created_by     uuid        NOT NULL REFERENCES user_account,
    reason         text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ticket_type_id, effective_from),
    UNIQUE (id, price),
    UNIQUE (id, ticket_type_id, price)
);

CREATE INDEX ticket_type_price_current_idx
    ON ticket_type_price (ticket_type_id, effective_from DESC);

-- Bổ sung khóa duy nhất trên seat(id, seat_map_id) để khóa ngoại ghép từ sku
-- kiểm tra trực tiếp ghế thuộc đúng seat_map của sự kiện.
ALTER TABLE seat
    ADD CONSTRAINT seat_id_seat_map_id_uk UNIQUE (id, seat_map_id);

-- -----------------------------------------------------------------------------
-- 2. Xác định hạng (ticket_type) từ dữ liệu cũ và chốt biểu giá trước khi gộp
-- -----------------------------------------------------------------------------
-- Chỉ gộp SKU cùng tên khi toàn bộ lịch giá giống nhau, kể cả giá đã hẹn.
DO $$
DECLARE
    v_conflict record;
BEGIN
    WITH schedules AS (
        SELECT s.id, s.event_id, btrim(s.name) AS tier_name,
               jsonb_agg(jsonb_build_array(sp.effective_from, sp.price)
                         ORDER BY sp.effective_from) FILTER (WHERE sp.id IS NOT NULL) AS prices
        FROM sku s
        LEFT JOIN sku_price sp ON sp.sku_id = s.id
        GROUP BY s.id
    )
    SELECT a.event_id, a.tier_name, a.id AS sku_a, b.id AS sku_b
    INTO v_conflict
    FROM schedules a
    JOIN schedules b ON b.event_id = a.event_id AND b.tier_name = a.tier_name AND b.id > a.id
    WHERE a.prices IS DISTINCT FROM b.prices
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'Xung đột lịch sử giá khi gộp hạng "%" của event % (SKU % và %)',
            v_conflict.tier_name, v_conflict.event_id, v_conflict.sku_a, v_conflict.sku_b;
    END IF;
END $$;

-- Chuyển hạng vé đã xác nhận sang ticket_type (giữ nguyên UUID của hạng chuẩn để
-- tương thích các tham chiếu hiện có).
WITH canonical_tier AS (
    SELECT DISTINCT ON (s.event_id, btrim(s.name))
           s.id,
           s.event_id,
           btrim(s.name) AS name,
           s.color
    FROM sku s
    LEFT JOIN event_zone ez ON ez.sku_id = s.id AND ez.event_id = s.event_id
    ORDER BY s.event_id, btrim(s.name), (ez.sku_id IS NOT NULL) DESC, s.id
)
INSERT INTO ticket_type (id, event_id, name, color)
SELECT id, event_id, name, color
FROM canonical_tier;

-- Chuyển lịch sử giá sang ticket_type_price (gộp theo hạng chuẩn và effective_from).
WITH tier_map AS (
    SELECT s.id AS legacy_sku_id,
           tt.id AS ticket_type_id
    FROM sku s
    JOIN ticket_type tt
      ON tt.event_id = s.event_id
     AND tt.name = btrim(s.name)
),
dedup_price AS (
    SELECT DISTINCT ON (tm.ticket_type_id, sp.effective_from)
           sp.id,
           tm.ticket_type_id,
           sp.price,
           sp.effective_from,
           sp.created_by,
           sp.reason,
           sp.created_at
    FROM sku_price sp
    JOIN tier_map tm ON tm.legacy_sku_id = sp.sku_id
    ORDER BY tm.ticket_type_id, sp.effective_from, ( sp.id = tm.ticket_type_id ) DESC, sp.created_at DESC, sp.id
)
INSERT INTO ticket_type_price (id, ticket_type_id, price, effective_from, created_by, reason, created_at)
SELECT id, ticket_type_id, price, effective_from, created_by, reason, created_at
FROM dedup_price;

-- Thêm cột ticket_type_id vào event_zone để giữ cấu hình hạng của khu trong giai đoạn chuyển tiếp
ALTER TABLE event_zone
    ADD COLUMN ticket_type_id uuid;

UPDATE event_zone ez
SET ticket_type_id = tt.id
FROM sku s
JOIN ticket_type tt ON tt.event_id = s.event_id AND tt.name = btrim(s.name)
WHERE ez.sku_id = s.id;

ALTER TABLE event_zone
    ADD CONSTRAINT event_zone_ticket_type_fk
        FOREIGN KEY (ticket_type_id, event_id) REFERENCES ticket_type (id, event_id);

CREATE INDEX event_zone_ticket_type_idx ON event_zone (ticket_type_id);

-- -----------------------------------------------------------------------------
-- 3. Mở rộng bảng sku, sinh SKU theo ghế / khu đứng và đối chiếu dữ liệu
-- -----------------------------------------------------------------------------
-- Mở rộng bảng sku: thêm ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind.
-- Giữ name, color nullable trên bảng sku để các luồng cũ insert vào sku vẫn hoạt động
-- qua trigger đồng bộ cho tới khi hoàn tất checklist 5.
ALTER TABLE sku
    ALTER COLUMN name DROP NOT NULL,
    ALTER COLUMN color DROP NOT NULL,
    ADD COLUMN ticket_type_id uuid,
    ADD COLUMN seat_map_id    uuid,
    ADD COLUMN seat_id        uuid,
    ADD COLUMN zone_id        uuid,
    ADD COLUMN zone_kind      zone_kind;

-- Bỏ constraint UNIQUE (event_id, name) cũ trên sku để một sự kiện có nhiều SKU
-- thuộc cùng một hạng vé (mỗi ghế 1 SKU).
ALTER TABLE sku
    DROP CONSTRAINT IF EXISTS sku_event_id_name_key;

-- Ràng buộc khóa ngoại ghép và kiểm tra ghế hoặc khu đứng trên sku:
-- * Hạng vé phải thuộc cùng sự kiện: (ticket_type_id, event_id) -> ticket_type (id, event_id)
-- * Sơ đồ ghế phải thuộc đúng sự kiện: (event_id, seat_map_id) -> event (id, seat_map_id)
-- * Nếu là SKU ghế ngồi: seat_id thuộc đúng seat_map_id (và bản thân bảng seat đã khóa zone_kind = 'SEATED')
-- * Nếu là SKU khu đứng: (zone_id, seat_map_id, zone_kind) -> zone (id, seat_map_id, kind) với zone_kind = 'STANDING'
ALTER TABLE sku
    ADD CONSTRAINT sku_ticket_type_event_fk
        FOREIGN KEY (ticket_type_id, event_id) REFERENCES ticket_type (id, event_id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT sku_event_seat_map_fk
        FOREIGN KEY (event_id, seat_map_id) REFERENCES event (id, seat_map_id),
    ADD CONSTRAINT sku_seat_seat_map_fk
        FOREIGN KEY (seat_id, seat_map_id) REFERENCES seat (id, seat_map_id),
    ADD CONSTRAINT sku_standing_zone_seat_map_fk
        FOREIGN KEY (zone_id, seat_map_id, zone_kind) REFERENCES zone (id, seat_map_id, kind),
    ADD CONSTRAINT sku_id_ticket_type_uk
        UNIQUE (id, ticket_type_id),
    ADD CONSTRAINT sku_id_event_seat_uk
        UNIQUE (id, event_id, seat_id),
    ADD CONSTRAINT sku_id_event_zone_uk
        UNIQUE (id, event_id, zone_id),
    ADD CONSTRAINT sku_target_seat_or_standing_chk
        CHECK (
            (seat_id IS NULL AND zone_id IS NULL AND zone_kind IS NULL AND name IS NOT NULL AND color IS NOT NULL)
            OR (
                ticket_type_id IS NOT NULL
                AND seat_map_id IS NOT NULL
                AND zone_kind IS NOT NULL
                AND (
                    (seat_id IS NOT NULL AND zone_id IS NULL AND zone_kind = 'SEATED')
                    OR (zone_id IS NOT NULL AND seat_id IS NULL AND zone_kind = 'STANDING')
                )
            )
        );

-- Khóa duy nhất theo (event_id, seat_id, ticket_type_id) cho vé ngồi và
-- (event_id, zone_id, ticket_type_id) cho vé đứng.
CREATE UNIQUE INDEX sku_event_seat_ticket_type_uk
    ON sku (event_id, seat_id, ticket_type_id)
    WHERE seat_id IS NOT NULL;

CREATE UNIQUE INDEX sku_event_zone_ticket_type_uk
    ON sku (event_id, zone_id, ticket_type_id)
    WHERE zone_id IS NOT NULL;

CREATE INDEX sku_event_ticket_type_idx
    ON sku (event_id, ticket_type_id);

-- Đối chiếu trước và sau khi sinh SKU từ cấu hình event_zone hiện có
DO $$
DECLARE
    v_seat_inv_before     bigint;
    v_standing_inv_before bigint;
    v_expected_seated_sku bigint;
    v_expected_stand_sku  bigint;
    v_actual_seated_sku   bigint;
    v_actual_stand_sku    bigint;
    v_seat_inv_after      bigint;
    v_standing_inv_after  bigint;
    v_price_mismatch      bigint;
BEGIN
    SELECT count(*) INTO v_seat_inv_before FROM seat_inventory;
    SELECT count(*) INTO v_standing_inv_before FROM standing_inventory;

    SELECT count(*)
    INTO v_expected_seated_sku
    FROM event_zone ez
    JOIN zone z ON z.id = ez.zone_id AND z.seat_map_id = ez.seat_map_id
    JOIN seat s ON s.zone_id = z.id AND s.seat_map_id = ez.seat_map_id
    WHERE z.kind = 'SEATED';

    SELECT count(*)
    INTO v_expected_stand_sku
    FROM event_zone ez
    JOIN zone z ON z.id = ez.zone_id AND z.seat_map_id = ez.seat_map_id
    WHERE z.kind = 'STANDING';

    -- Sinh một SKU cho mỗi cặp ghế và hạng đã cấu hình ở khu SEATED
    INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
    SELECT ez.event_id, ez.ticket_type_id, ez.seat_map_id, s.id, NULL, 'SEATED'::zone_kind
    FROM event_zone ez
    JOIN zone z ON z.id = ez.zone_id AND z.seat_map_id = ez.seat_map_id
    JOIN seat s ON s.zone_id = z.id AND s.seat_map_id = ez.seat_map_id
    WHERE z.kind = 'SEATED'
    ON CONFLICT (event_id, seat_id, ticket_type_id) WHERE seat_id IS NOT NULL DO NOTHING;

    -- Sinh một SKU cho mỗi cặp khu và hạng đã cấu hình ở khu STANDING
    INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
    SELECT ez.event_id, ez.ticket_type_id, ez.seat_map_id, NULL, z.id, 'STANDING'::zone_kind
    FROM event_zone ez
    JOIN zone z ON z.id = ez.zone_id AND z.seat_map_id = ez.seat_map_id
    WHERE z.kind = 'STANDING'
    ON CONFLICT (event_id, zone_id, ticket_type_id) WHERE zone_id IS NOT NULL DO NOTHING;

    -- Đối chiếu số lượng SKU đã sinh
    SELECT count(*) INTO v_actual_seated_sku FROM sku WHERE seat_id IS NOT NULL;
    SELECT count(*) INTO v_actual_stand_sku  FROM sku WHERE zone_id IS NOT NULL;

    IF v_actual_seated_sku <> v_expected_seated_sku THEN
        RAISE EXCEPTION 'Đối chiếu số SKU ghế ngồi thất bại: kỳ vọng %, thực tế %',
            v_expected_seated_sku, v_actual_seated_sku;
    END IF;

    IF v_actual_stand_sku <> v_expected_stand_sku THEN
        RAISE EXCEPTION 'Đối chiếu số SKU khu đứng thất bại: kỳ vọng %, thực tế %',
            v_expected_stand_sku, v_actual_stand_sku;
    END IF;

    -- Đối chiếu số dòng tồn kho không bị thay đổi
    SELECT count(*) INTO v_seat_inv_after FROM seat_inventory;
    SELECT count(*) INTO v_standing_inv_after FROM standing_inventory;

    IF v_seat_inv_after <> v_seat_inv_before OR v_standing_inv_after <> v_standing_inv_before THEN
        RAISE EXCEPTION 'Đối chiếu tồn kho thất bại: seat_inventory % -> %, standing_inventory % -> %',
            v_seat_inv_before, v_seat_inv_after, v_standing_inv_before, v_standing_inv_after;
    END IF;

    -- Đối chiếu giá có hiệu lực của từng khu/ghế trước (sku_price) và sau (ticket_type_price)
    SELECT count(*)
    INTO v_price_mismatch
    FROM event_zone ez
    LEFT JOIN LATERAL (
        SELECT price
        FROM sku_price sp
        WHERE sp.sku_id = ez.sku_id AND sp.effective_from <= now()
        ORDER BY sp.effective_from DESC
        LIMIT 1
    ) old_p ON true
    LEFT JOIN LATERAL (
        SELECT price
        FROM ticket_type_price ttp
        WHERE ttp.ticket_type_id = ez.ticket_type_id AND ttp.effective_from <= now()
        ORDER BY ttp.effective_from DESC
        LIMIT 1
    ) new_p ON true
    WHERE old_p.price IS DISTINCT FROM new_p.price;

    IF v_price_mismatch > 0 THEN
        RAISE EXCEPTION 'Đối chiếu giá có hiệu lực thất bại: % khu lệch giá giữa sku_price và ticket_type_price',
            v_price_mismatch;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 4. Chuyển dòng đơn sang ticket_type_price và giữ đường đọc đơn cũ
-- -----------------------------------------------------------------------------
ALTER TABLE showtime ADD UNIQUE (id, event_id);

ALTER TABLE order_seat
    ADD COLUMN event_id uuid,
    ALTER COLUMN sku_price_id DROP NOT NULL,
    ADD COLUMN sku_id               uuid,
    ADD COLUMN ticket_type_id       uuid,
    ADD COLUMN ticket_type_price_id uuid;

ALTER TABLE order_standing
    ADD COLUMN event_id uuid,
    ALTER COLUMN sku_price_id DROP NOT NULL,
    ADD COLUMN sku_id               uuid,
    ADD COLUMN ticket_type_id       uuid,
    ADD COLUMN ticket_type_price_id uuid;

-- Điền ticket_type_id, ticket_type_price_id và sku_id cho dữ liệu order_seat hiện có
UPDATE order_seat os
SET event_id             = st.event_id,
    ticket_type_id       = tt.id,
    ticket_type_price_id = ttp.id,
    sku_id               = (
        SELECT seat_sku.id
        FROM sku seat_sku
        WHERE seat_sku.event_id = tt.event_id
          AND seat_sku.ticket_type_id = tt.id
          AND seat_sku.seat_id = os.seat_id
    )
FROM showtime st,
     sku_price sp
JOIN sku legacy_s ON legacy_s.id = sp.sku_id
JOIN ticket_type tt ON tt.event_id = legacy_s.event_id AND tt.name = btrim(legacy_s.name)
JOIN ticket_type_price ttp ON ttp.ticket_type_id = tt.id AND ttp.effective_from = sp.effective_from
WHERE st.id = os.showtime_id
  AND sp.id = os.sku_price_id;

-- Điền ticket_type_id, ticket_type_price_id và sku_id cho dữ liệu order_standing hiện có
UPDATE order_standing ost
SET event_id             = st.event_id,
    ticket_type_id       = tt.id,
    ticket_type_price_id = ttp.id,
    sku_id               = (
        SELECT zone_sku.id
        FROM sku zone_sku
        WHERE zone_sku.event_id = tt.event_id
          AND zone_sku.ticket_type_id = tt.id
          AND zone_sku.zone_id = ost.zone_id
    )
FROM showtime st,
     sku_price sp
JOIN sku legacy_s ON legacy_s.id = sp.sku_id
JOIN ticket_type tt ON tt.event_id = legacy_s.event_id AND tt.name = btrim(legacy_s.name)
JOIN ticket_type_price ttp ON ttp.ticket_type_id = tt.id AND ttp.effective_from = sp.effective_from
WHERE st.id = ost.showtime_id
  AND sp.id = ost.sku_price_id;

-- Ràng buộc khóa ngoại trên order_seat và order_standing:
-- * Phiên bản giá phải thuộc đúng hạng của SKU và khớp unit_price đã chốt
-- * Giữ nguyên đường đọc đơn cũ qua sku_price_id
ALTER TABLE order_seat
    ALTER COLUMN event_id SET NOT NULL,
    ADD CONSTRAINT order_seat_showtime_event_fk
        FOREIGN KEY (showtime_id, event_id) REFERENCES showtime (id, event_id),
    ADD CONSTRAINT order_seat_sku_target_fk
        FOREIGN KEY (sku_id, event_id, seat_id) REFERENCES sku (id, event_id, seat_id),
    ADD CONSTRAINT order_seat_sku_ticket_type_fk
        FOREIGN KEY (sku_id, ticket_type_id) REFERENCES sku (id, ticket_type_id),
    ADD CONSTRAINT order_seat_ticket_type_price_fk
        FOREIGN KEY (ticket_type_price_id, ticket_type_id, unit_price)
        REFERENCES ticket_type_price (id, ticket_type_id, price),
    ADD CONSTRAINT order_seat_price_ref_chk
        CHECK ((ticket_type_price_id IS NOT NULL AND ticket_type_id IS NOT NULL) OR sku_price_id IS NOT NULL),
    ADD CONSTRAINT order_seat_sku_requires_ticket_type_chk
        CHECK (sku_id IS NULL OR ticket_type_id IS NOT NULL);

ALTER TABLE order_standing
    ALTER COLUMN event_id SET NOT NULL,
    ADD CONSTRAINT order_standing_showtime_event_fk
        FOREIGN KEY (showtime_id, event_id) REFERENCES showtime (id, event_id),
    ADD CONSTRAINT order_standing_sku_target_fk
        FOREIGN KEY (sku_id, event_id, zone_id) REFERENCES sku (id, event_id, zone_id),
    ADD CONSTRAINT order_standing_sku_ticket_type_fk
        FOREIGN KEY (sku_id, ticket_type_id) REFERENCES sku (id, ticket_type_id),
    ADD CONSTRAINT order_standing_ticket_type_price_fk
        FOREIGN KEY (ticket_type_price_id, ticket_type_id, unit_price)
        REFERENCES ticket_type_price (id, ticket_type_id, price),
    ADD CONSTRAINT order_standing_price_ref_chk
        CHECK ((ticket_type_price_id IS NOT NULL AND ticket_type_id IS NOT NULL) OR sku_price_id IS NOT NULL),
    ADD CONSTRAINT order_standing_sku_requires_ticket_type_chk
        CHECK (sku_id IS NULL OR ticket_type_id IS NOT NULL);

CREATE INDEX order_seat_ticket_type_price_idx ON order_seat (ticket_type_price_id);
CREATE INDEX order_standing_ticket_type_price_idx ON order_standing (ticket_type_price_id);

-- Đối chiếu các dòng đơn đã chuyển sang ticket_type_price và giữ nguyên unit_price
DO $$
DECLARE
    v_unmapped_seat     bigint;
    v_unmapped_standing bigint;
BEGIN
    SELECT count(*)
    INTO v_unmapped_seat
    FROM order_seat os
    LEFT JOIN ticket_type_price ttp ON ttp.id = os.ticket_type_price_id
    WHERE os.ticket_type_price_id IS NULL
       OR ttp.price IS DISTINCT FROM os.unit_price;

    IF v_unmapped_seat > 0 THEN
        RAISE EXCEPTION 'Đối chiếu order_seat thất bại: % dòng chưa khớp ticket_type_price hoặc lệch unit_price',
            v_unmapped_seat;
    END IF;

    SELECT count(*)
    INTO v_unmapped_standing
    FROM order_standing ost
    LEFT JOIN ticket_type_price ttp ON ttp.id = ost.ticket_type_price_id
    WHERE ost.ticket_type_price_id IS NULL
       OR ttp.price IS DISTINCT FROM ost.unit_price;

    IF v_unmapped_standing > 0 THEN
        RAISE EXCEPTION 'Đối chiếu order_standing thất bại: % dòng chưa khớp ticket_type_price hoặc lệch unit_price',
            v_unmapped_standing;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- Trigger đồng bộ hai chiều trong giai đoạn chuyển tiếp (trước checklist 5)
-- Giúp cả luồng mới (ticket_type / ticket_type_price) và các fixture/test cũ
-- (sku / sku_price / event_zone / order_seat / order_standing) luôn nhất quán.
-- -----------------------------------------------------------------------------

-- 1) Đồng bộ ticket_type <-> legacy sku (dòng hạng vé có seat_id IS NULL AND zone_id IS NULL)
CREATE OR REPLACE FUNCTION sync_legacy_sku_and_ticket_type()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() > 1 THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'sku' THEN
        IF TG_OP = 'INSERT' THEN
            IF NEW.seat_id IS NULL AND NEW.zone_id IS NULL THEN
                INSERT INTO ticket_type (id, event_id, name, color)
                VALUES (NEW.id, NEW.event_id, btrim(NEW.name), NEW.color)
                ON CONFLICT (id) DO UPDATE
                    SET name  = EXCLUDED.name,
                        color = EXCLUDED.color;
                NEW.ticket_type_id := NEW.id;
            END IF;
            RETURN NEW;
        ELSIF TG_OP = 'UPDATE' THEN
            IF NEW.seat_id IS NULL AND NEW.zone_id IS NULL THEN
                UPDATE ticket_type
                SET name  = btrim(NEW.name),
                    color = NEW.color
                WHERE id = NEW.id;
            END IF;
            RETURN NEW;
        ELSIF TG_OP = 'DELETE' THEN
            IF OLD.seat_id IS NULL AND OLD.zone_id IS NULL THEN
                DELETE FROM sku
                WHERE event_id = OLD.event_id
                  AND ticket_type_id = OLD.id
                  AND (seat_id IS NOT NULL OR zone_id IS NOT NULL);
                DELETE FROM ticket_type_price WHERE ticket_type_id = OLD.id;
                DELETE FROM ticket_type WHERE id = OLD.id;
            END IF;
            RETURN OLD;
        END IF;
    ELSIF TG_TABLE_NAME = 'ticket_type' THEN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO sku (id, event_id, name, color, ticket_type_id)
            VALUES (NEW.id, NEW.event_id, NEW.name, NEW.color, NEW.id)
            ON CONFLICT (id) DO UPDATE
                SET name           = EXCLUDED.name,
                    color          = EXCLUDED.color,
                    ticket_type_id = EXCLUDED.ticket_type_id;
            RETURN NEW;
        ELSIF TG_OP = 'UPDATE' THEN
            UPDATE sku
            SET name  = NEW.name,
                color = NEW.color
            WHERE id = NEW.id
              AND seat_id IS NULL
              AND zone_id IS NULL;
            RETURN NEW;
        ELSIF TG_OP = 'DELETE' THEN
            DELETE FROM sku
            WHERE event_id = OLD.event_id
              AND (id = OLD.id OR ticket_type_id = OLD.id);
            RETURN OLD;
        END IF;
    END IF;

    RETURN NULL;
END $$;

CREATE TRIGGER trg_sync_legacy_sku_to_ticket_type
    BEFORE INSERT OR UPDATE OR DELETE ON sku
    FOR EACH ROW
    EXECUTE FUNCTION sync_legacy_sku_and_ticket_type();

CREATE TRIGGER trg_sync_ticket_type_to_legacy_sku
    AFTER INSERT OR UPDATE OR DELETE ON ticket_type
    FOR EACH ROW
    EXECUTE FUNCTION sync_legacy_sku_and_ticket_type();

-- Điền ticket_type_id cho các dòng legacy sku đã có từ trước
UPDATE sku
SET ticket_type_id = tt.id
FROM ticket_type tt
WHERE sku.seat_id IS NULL
  AND sku.zone_id IS NULL
  AND tt.event_id = sku.event_id
  AND tt.name = btrim(sku.name);

-- 2) Đồng bộ ticket_type_price <-> sku_price
CREATE OR REPLACE FUNCTION sync_sku_price_and_ticket_type_price()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_ticket_type_id uuid;
BEGIN
    IF pg_trigger_depth() > 1 THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'sku_price' THEN
        IF TG_OP = 'INSERT' THEN
            SELECT COALESCE(s.ticket_type_id, s.id)
            INTO v_ticket_type_id
            FROM sku s
            WHERE s.id = NEW.sku_id;

            INSERT INTO ticket_type_price (id, ticket_type_id, price, effective_from, created_by, reason, created_at)
            VALUES (NEW.id, v_ticket_type_id, NEW.price, NEW.effective_from, NEW.created_by, NEW.reason, NEW.created_at)
            ON CONFLICT (id) DO NOTHING;
            RETURN NEW;
        ELSIF TG_OP = 'DELETE' THEN
            DELETE FROM ticket_type_price WHERE id = OLD.id;
            RETURN OLD;
        END IF;
    ELSIF TG_TABLE_NAME = 'ticket_type_price' THEN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO sku_price (id, sku_id, price, effective_from, created_by, reason, created_at)
            VALUES (NEW.id, NEW.ticket_type_id, NEW.price, NEW.effective_from, NEW.created_by, NEW.reason, NEW.created_at)
            ON CONFLICT (id) DO NOTHING;
            RETURN NEW;
        ELSIF TG_OP = 'DELETE' THEN
            DELETE FROM sku_price WHERE id = OLD.id;
            RETURN OLD;
        END IF;
    END IF;

    RETURN NULL;
END $$;

CREATE TRIGGER trg_sync_sku_price_to_ticket_type_price
    AFTER INSERT OR DELETE ON sku_price
    FOR EACH ROW
    EXECUTE FUNCTION sync_sku_price_and_ticket_type_price();

CREATE TRIGGER trg_sync_ticket_type_price_to_sku_price
    AFTER INSERT OR DELETE ON ticket_type_price
    FOR EACH ROW
    EXECUTE FUNCTION sync_sku_price_and_ticket_type_price();

-- 3) Tự động điền ticket_type_id và sinh SKU theo ghế / khu đứng khi cấu hình event_zone hoặc thêm ghế
CREATE OR REPLACE FUNCTION sync_event_zone_skus()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_zone_kind zone_kind;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT z.kind INTO v_zone_kind FROM zone z WHERE z.id = OLD.zone_id;
        IF v_zone_kind = 'SEATED' THEN
            DELETE FROM sku s
            USING seat se
            WHERE s.event_id = OLD.event_id
              AND s.ticket_type_id = COALESCE(OLD.ticket_type_id, OLD.sku_id)
              AND s.seat_id = se.id
              AND se.zone_id = OLD.zone_id
              AND NOT EXISTS (SELECT 1 FROM order_seat os WHERE os.sku_id = s.id);
        ELSE
            DELETE FROM sku s
            WHERE s.event_id = OLD.event_id
              AND s.ticket_type_id = COALESCE(OLD.ticket_type_id, OLD.sku_id)
              AND s.zone_id = OLD.zone_id
              AND NOT EXISTS (SELECT 1 FROM order_standing ost WHERE ost.sku_id = s.id);
        END IF;
        RETURN OLD;
    END IF;

    IF NEW.ticket_type_id IS NULL THEN
        SELECT COALESCE(s.ticket_type_id, s.id)
        INTO NEW.ticket_type_id
        FROM sku s
        WHERE s.id = NEW.sku_id;
    END IF;

    IF NEW.sku_id IS NULL THEN
        NEW.sku_id := NEW.ticket_type_id;
    END IF;

    SELECT z.kind INTO v_zone_kind
    FROM zone z
    WHERE z.id = NEW.zone_id AND z.seat_map_id = NEW.seat_map_id;

    IF v_zone_kind = 'SEATED' THEN
        INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
        SELECT NEW.event_id, NEW.ticket_type_id, NEW.seat_map_id, se.id, NULL, 'SEATED'::zone_kind
        FROM seat se
        WHERE se.zone_id = NEW.zone_id AND se.seat_map_id = NEW.seat_map_id
        ON CONFLICT (event_id, seat_id, ticket_type_id) WHERE seat_id IS NOT NULL DO NOTHING;
    ELSIF v_zone_kind = 'STANDING' THEN
        INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
        VALUES (NEW.event_id, NEW.ticket_type_id, NEW.seat_map_id, NULL, NEW.zone_id, 'STANDING'::zone_kind)
        ON CONFLICT (event_id, zone_id, ticket_type_id) WHERE zone_id IS NOT NULL DO NOTHING;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_sync_event_zone_skus
    BEFORE INSERT OR UPDATE OR DELETE ON event_zone
    FOR EACH ROW
    EXECUTE FUNCTION sync_event_zone_skus();

CREATE OR REPLACE FUNCTION sync_seat_to_event_skus()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
    SELECT ez.event_id, COALESCE(ez.ticket_type_id, ez.sku_id), ez.seat_map_id, NEW.id, NULL, 'SEATED'::zone_kind
    FROM event_zone ez
    WHERE ez.zone_id = NEW.zone_id AND ez.seat_map_id = NEW.seat_map_id
    ON CONFLICT (event_id, seat_id, ticket_type_id) WHERE seat_id IS NOT NULL DO NOTHING;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_sync_seat_to_event_skus
    AFTER INSERT ON seat
    FOR EACH ROW
    EXECUTE FUNCTION sync_seat_to_event_skus();

-- 4) Đồng bộ các cột giá trên order_seat và order_standing khi chỉ truyền đường cũ hoặc đường mới
CREATE OR REPLACE FUNCTION sync_order_line_pricing()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_event_id uuid;
BEGIN
    SELECT st.event_id INTO v_event_id
    FROM showtime st
    WHERE st.id = NEW.showtime_id;
    NEW.event_id := v_event_id;

    IF NEW.ticket_type_price_id IS NULL AND NEW.sku_price_id IS NOT NULL THEN
        SELECT ttp.id, ttp.ticket_type_id
        INTO NEW.ticket_type_price_id, NEW.ticket_type_id
        FROM sku_price sp
        JOIN sku s ON s.id = sp.sku_id
        JOIN ticket_type_price ttp
          ON ttp.ticket_type_id = COALESCE(s.ticket_type_id, s.id)
         AND ttp.effective_from = sp.effective_from
        WHERE sp.id = NEW.sku_price_id;
    ELSIF NEW.sku_price_id IS NULL AND NEW.ticket_type_price_id IS NOT NULL THEN
        SELECT ttp.ticket_type_id
        INTO NEW.ticket_type_id
        FROM ticket_type_price ttp
        WHERE ttp.id = NEW.ticket_type_price_id;

        SELECT sp.id
        INTO NEW.sku_price_id
        FROM ticket_type_price ttp
        JOIN sku_price sp
          ON sp.sku_id = ttp.ticket_type_id
         AND sp.effective_from = ttp.effective_from
        WHERE ttp.id = NEW.ticket_type_price_id;
    END IF;

    IF NEW.ticket_type_id IS NULL AND NEW.ticket_type_price_id IS NOT NULL THEN
        SELECT ttp.ticket_type_id
        INTO NEW.ticket_type_id
        FROM ticket_type_price ttp
        WHERE ttp.id = NEW.ticket_type_price_id;
    END IF;

    IF NEW.sku_id IS NULL AND NEW.ticket_type_id IS NOT NULL THEN
        IF TG_TABLE_NAME = 'order_seat' THEN
            SELECT s.id
            INTO NEW.sku_id
            FROM sku s
            WHERE s.event_id = v_event_id
              AND s.ticket_type_id = NEW.ticket_type_id
              AND s.seat_id = NEW.seat_id;
        ELSIF TG_TABLE_NAME = 'order_standing' THEN
            SELECT s.id
            INTO NEW.sku_id
            FROM sku s
            WHERE s.event_id = v_event_id
              AND s.ticket_type_id = NEW.ticket_type_id
              AND s.zone_id = NEW.zone_id;
        END IF;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_sync_order_seat_pricing
    BEFORE INSERT OR UPDATE ON order_seat
    FOR EACH ROW
    EXECUTE FUNCTION sync_order_line_pricing();

CREATE TRIGGER trg_sync_order_standing_pricing
    BEFORE INSERT OR UPDATE ON order_standing
    FOR EACH ROW
    EXECUTE FUNCTION sync_order_line_pricing();
