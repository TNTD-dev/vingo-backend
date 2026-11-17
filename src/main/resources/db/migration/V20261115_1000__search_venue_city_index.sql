-- Tìm sự kiện theo tên sân và thành phố của suất.
-- venue.city lưu mã (HN, HCM, DN) nên q "Hà Nội" không thể khớp trực tiếp. Hàm dưới đổi mã thành chuỗi tên để
-- đưa vào full-text. Danh sách này TẠM, cùng nguồn với CITY_NAMES ở SearchApiMapper; catalog chốt danh sách mới
-- thì thêm migration mới thay hàm (CREATE OR REPLACE) rồi REINDEX.
CREATE FUNCTION immutable_city_text(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT CASE $1
                     WHEN 'HN' THEN 'Hà Nội Hanoi HN'
                     WHEN 'HCM' THEN 'TP. Hồ Chí Minh Sài Gòn Saigon HCM'
                     WHEN 'DN' THEN 'Đà Nẵng Da Nang DN'
                     ELSE coalesce($1, '')
                 END $$;

-- Tài liệu tìm kiếm của sân: tên sân + tên thành phố, bỏ dấu.
CREATE INDEX venue_search_fts_idx ON venue
    USING gin (to_tsvector('simple', immutable_unaccent(coalesce(name, '') || ' ' || immutable_city_text(city))));

-- Tiêu đề riêng (không kèm mô tả) để xếp khớp tên sự kiện trên khớp mô tả.
CREATE INDEX event_title_only_fts_idx ON event
    USING gin (to_tsvector('simple', immutable_unaccent(coalesce(title, ''))));

-- Đi từ sân khớp sang suất: venue -> hall (đã có unique venue_id, name) -> showtime. Chỉ suất SCHEDULED hiện ra.
CREATE INDEX showtime_hall_scheduled_idx ON showtime (hall_id, event_id) WHERE status = 'SCHEDULED';
