ALTER TABLE seat_layout ADD COLUMN name text;

-- Giữ nguyên ID/phiên bản và liên kết suất của sơ đồ cũ.
UPDATE seat_layout SET name = 'Sơ đồ v' || version;

ALTER TABLE seat_layout
    ALTER COLUMN name SET NOT NULL,
    -- Tương thích seed/fixture nội bộ cũ; API import luôn bắt buộc nhập tên.
    ALTER COLUMN name SET DEFAULT 'Sơ đồ',
    ADD CONSTRAINT seat_layout_name_check CHECK (
        name ~ '[^[:space:]]' AND length(name) <= 200
    );
