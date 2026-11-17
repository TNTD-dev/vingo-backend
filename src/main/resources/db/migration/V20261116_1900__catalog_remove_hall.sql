-- MIGRATION CHỜ TRIỂN KHAI: chưa nằm trong đường dẫn tự chạy của Flyway.
-- Chỉ chuyển vào src/main/resources/db/migration sau khi backend/UI đã bỏ hall.
-- Flyway phải chạy trong transaction. Nếu chạy bằng psql, dùng --single-transaction
-- và -v ON_ERROR_STOP=1. Không chạy riêng lẻ từng câu lệnh.

LOCK TABLE venue, hall, seat_layout, showtime IN ACCESS EXCLUSIVE MODE;

-- Không tự sửa dữ liệu lệch quan hệ: dừng và rollback để người quản lý xử lý.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM showtime s
        JOIN seat_layout sl ON sl.id = s.seat_layout_id
        WHERE s.hall_id IS DISTINCT FROM sl.hall_id
    ) THEN
        RAISE EXCEPTION 'REMOVE_HALL_PREFLIGHT: showtime.hall_id does not match seat_layout.hall_id';
    END IF;

    IF EXISTS (
        SELECT h.venue_id, sl.version
        FROM seat_layout sl JOIN hall h ON h.id = sl.hall_id
        GROUP BY h.venue_id, sl.version HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'REMOVE_HALL_PREFLIGHT: duplicate layout version within a venue';
    END IF;
END $$;

ALTER TABLE seat_layout ADD COLUMN venue_id uuid;

UPDATE seat_layout sl SET venue_id = h.venue_id
FROM hall h WHERE h.id = sl.hall_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM seat_layout WHERE venue_id IS NULL) THEN
        RAISE EXCEPTION 'REMOVE_HALL_PREFLIGHT: seat_layout has no venue';
    END IF;
END $$;

ALTER TABLE seat_layout
    ALTER COLUMN venue_id SET NOT NULL,
    ADD CONSTRAINT seat_layout_venue_id_fkey FOREIGN KEY (venue_id) REFERENCES venue (id),
    ADD CONSTRAINT seat_layout_venue_version_uk UNIQUE (venue_id, version);

CREATE INDEX seat_layout_venue_idx ON seat_layout (venue_id);

ALTER TABLE showtime DROP CONSTRAINT showtime_hall_id_fkey;
ALTER TABLE showtime DROP COLUMN hall_id;

ALTER TABLE seat_layout
    DROP CONSTRAINT seat_layout_hall_version_uk,
    DROP CONSTRAINT seat_layout_hall_id_fkey;
DROP INDEX seat_layout_hall_idx;
ALTER TABLE seat_layout DROP COLUMN hall_id;

-- RESTRICT mặc định: nếu còn đối tượng ngoài dự kiến phụ thuộc hall thì rollback,
-- không tự xóa bảng/view liên quan bằng CASCADE.
DROP TABLE hall;
