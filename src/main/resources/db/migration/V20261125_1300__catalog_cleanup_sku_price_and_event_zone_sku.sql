-- =============================================================================
-- Checklist 5 trong docs/ticket-sku-redesign.md (mục "Schema và chuyển dữ liệu"):
-- "Khi các luồng đã đọc mô hình mới và dữ liệu lịch sử đã được xác minh,
--  dùng migration dọn sku_price và liên kết event_zone.sku_id."
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Xác minh lần cuối toàn bộ dữ liệu lịch sử đã chuyển sang mô hình mới
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_missing_ez_type       bigint;
    v_missing_os_price      bigint;
    v_missing_ost_price     bigint;
    v_missing_ttp_rows      bigint;
BEGIN
    SELECT count(*) INTO v_missing_ez_type
    FROM event_zone
    WHERE ticket_type_id IS NULL;

    IF v_missing_ez_type > 0 THEN
        RAISE EXCEPTION 'Không thể dọn event_zone.sku_id: còn % dòng event_zone thiếu ticket_type_id',
            v_missing_ez_type;
    END IF;

    SELECT count(*) INTO v_missing_os_price
    FROM order_seat
    WHERE ticket_type_price_id IS NULL OR ticket_type_id IS NULL;

    IF v_missing_os_price > 0 THEN
        RAISE EXCEPTION 'Không thể dọn order_seat.sku_price_id: còn % dòng order_seat thiếu ticket_type_price_id',
            v_missing_os_price;
    END IF;

    SELECT count(*) INTO v_missing_ost_price
    FROM order_standing
    WHERE ticket_type_price_id IS NULL OR ticket_type_id IS NULL;

    IF v_missing_ost_price > 0 THEN
        RAISE EXCEPTION 'Không thể dọn order_standing.sku_price_id: còn % dòng order_standing thiếu ticket_type_price_id',
            v_missing_ost_price;
    END IF;

    SELECT count(*) INTO v_missing_ttp_rows
    FROM sku_price sp
    JOIN sku legacy_sku ON legacy_sku.id = sp.sku_id
    LEFT JOIN ticket_type_price ttp
      ON ttp.ticket_type_id = legacy_sku.ticket_type_id
     AND ttp.effective_from = sp.effective_from
     AND ttp.price = sp.price
    WHERE ttp.id IS NULL;

    IF v_missing_ttp_rows > 0 THEN
        RAISE EXCEPTION 'Không thể dọn sku_price: còn % dòng lịch sử giá chưa có trong ticket_type_price',
            v_missing_ttp_rows;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 2. Gỡ các trigger đồng bộ tạm với bảng sku_price và dòng hạng vé legacy trên sku
-- -----------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_sync_sku_price_to_ticket_type_price ON sku_price;
DROP TRIGGER IF EXISTS trg_sync_ticket_type_price_to_sku_price ON ticket_type_price;
DROP FUNCTION IF EXISTS sync_sku_price_and_ticket_type_price();

DROP TRIGGER IF EXISTS trg_sync_legacy_sku_to_ticket_type ON sku;
DROP TRIGGER IF EXISTS trg_sync_ticket_type_to_legacy_sku ON ticket_type;
DROP FUNCTION IF EXISTS sync_legacy_sku_and_ticket_type();

-- -----------------------------------------------------------------------------
-- 3. Dọn tham chiếu sku_price_id trên order_seat / order_standing và xóa bảng sku_price
-- -----------------------------------------------------------------------------
ALTER TABLE order_seat
    DROP CONSTRAINT IF EXISTS order_seat_price_ref_chk,
    DROP CONSTRAINT IF EXISTS order_seat_sku_price_id_unit_price_fkey,
    DROP COLUMN IF EXISTS sku_price_id,
    ALTER COLUMN ticket_type_id SET NOT NULL,
    ALTER COLUMN ticket_type_price_id SET NOT NULL;

ALTER TABLE order_standing
    DROP CONSTRAINT IF EXISTS order_standing_price_ref_chk,
    DROP CONSTRAINT IF EXISTS order_standing_sku_price_id_unit_price_fkey,
    DROP COLUMN IF EXISTS sku_price_id,
    ALTER COLUMN ticket_type_id SET NOT NULL,
    ALTER COLUMN ticket_type_price_id SET NOT NULL;

DROP TABLE sku_price;

-- -----------------------------------------------------------------------------
-- 4. Dọn liên kết event_zone.sku_id và chốt event_zone.ticket_type_id
-- -----------------------------------------------------------------------------
DROP INDEX IF EXISTS event_zone_sku_idx;

ALTER TABLE event_zone
    DROP CONSTRAINT IF EXISTS event_zone_sku_id_event_id_fkey,
    DROP COLUMN IF EXISTS sku_id,
    ALTER COLUMN ticket_type_id SET NOT NULL;

-- -----------------------------------------------------------------------------
-- 5. Dọn các dòng hạng vé cũ (không gắn ghế hay khu đứng) khỏi bảng sku
--    và siết chặt ràng buộc bảng sku chỉ chứa SKU ghế ngồi hoặc khu đứng
-- -----------------------------------------------------------------------------
DELETE FROM sku
WHERE seat_id IS NULL
  AND zone_id IS NULL;

ALTER TABLE sku
    DROP CONSTRAINT IF EXISTS sku_target_seat_or_standing_chk,
    DROP COLUMN IF EXISTS name,
    DROP COLUMN IF EXISTS color,
    ALTER COLUMN ticket_type_id SET NOT NULL,
    ALTER COLUMN seat_map_id SET NOT NULL,
    ALTER COLUMN zone_kind SET NOT NULL,
    ADD CONSTRAINT sku_target_seat_or_standing_chk
        CHECK (
            (seat_id IS NOT NULL AND zone_id IS NULL AND zone_kind = 'SEATED')
            OR (zone_id IS NOT NULL AND seat_id IS NULL AND zone_kind = 'STANDING')
        );

-- -----------------------------------------------------------------------------
-- 6. Cập nhật trigger sinh SKU từ event_zone / seat và điền sku_id cho dòng đơn
--    sau khi đã bỏ sku_price và event_zone.sku_id
-- -----------------------------------------------------------------------------
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
              AND s.ticket_type_id = OLD.ticket_type_id
              AND s.seat_id = se.id
              AND se.zone_id = OLD.zone_id
              AND NOT EXISTS (SELECT 1 FROM order_seat os WHERE os.sku_id = s.id);
        ELSE
            DELETE FROM sku s
            WHERE s.event_id = OLD.event_id
              AND s.ticket_type_id = OLD.ticket_type_id
              AND s.zone_id = OLD.zone_id
              AND NOT EXISTS (SELECT 1 FROM order_standing ost WHERE ost.sku_id = s.id);
        END IF;
        RETURN OLD;
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

CREATE OR REPLACE FUNCTION sync_seat_to_event_skus()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
    SELECT ez.event_id, ez.ticket_type_id, ez.seat_map_id, NEW.id, NULL, 'SEATED'::zone_kind
    FROM event_zone ez
    WHERE ez.zone_id = NEW.zone_id AND ez.seat_map_id = NEW.seat_map_id
    ON CONFLICT (event_id, seat_id, ticket_type_id) WHERE seat_id IS NOT NULL DO NOTHING;
    RETURN NEW;
END $$;

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
