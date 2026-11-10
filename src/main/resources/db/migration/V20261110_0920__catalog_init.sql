-- Catalog: sân, sơ đồ ghế, sự kiện, suất diễn, giá.
CREATE TABLE venue (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    name       text        NOT NULL,
    type       text        NOT NULL CHECK (type IN ('STADIUM', 'THEATER', 'OUTDOOR')),
    address    text,
    city       text,
    info       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE hall (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    venue_id   uuid        NOT NULL REFERENCES venue (id),
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (venue_id, name)
);

CREATE TABLE seat_layout (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    hall_id      uuid        NOT NULL REFERENCES hall (id),
    status       text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX seat_layout_hall_idx ON seat_layout (hall_id);

CREATE TABLE seat_type (
    id   uuid PRIMARY KEY DEFAULT uuidv7(),
    code text NOT NULL UNIQUE,
    name text NOT NULL
);
INSERT INTO seat_type (code, name) VALUES
    ('STANDARD', 'Ghế thường'),
    ('COUPLE', 'Ghế đôi'),
    ('WHEELCHAIR', 'Chỗ xe lăn');

CREATE TABLE zone (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    seat_layout_id uuid NOT NULL REFERENCES seat_layout (id),
    name           text NOT NULL,
    kind           text NOT NULL CHECK (kind IN ('SEATED', 'STANDING')),
    capacity       int  NOT NULL CHECK (capacity >= 0),
    UNIQUE (seat_layout_id, name)
);

-- row_label thay cho "row" trong ERD để tránh từ khoá SQL. x, y là toạ độ vẽ sơ đồ.
CREATE TABLE seat (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    zone_id      uuid NOT NULL REFERENCES zone (id),
    seat_type_id uuid NOT NULL REFERENCES seat_type (id),
    row_label    text NOT NULL,
    number       int  NOT NULL CHECK (number > 0),
    x            int  NOT NULL,
    y            int  NOT NULL,
    UNIQUE (zone_id, row_label, number)
);

-- Ghế đôi: mỗi ghế thuộc tối đa một cặp, lưu theo thứ tự id để không trùng (a,b) và (b,a).
CREATE TABLE seat_pair (
    seat_a_id uuid NOT NULL UNIQUE REFERENCES seat (id),
    seat_b_id uuid NOT NULL UNIQUE REFERENCES seat (id),
    PRIMARY KEY (seat_a_id, seat_b_id),
    CHECK (seat_a_id < seat_b_id)
);

CREATE TABLE event (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    title                 text        NOT NULL,
    description           text,
    category              text        NOT NULL CHECK (category IN ('FOOTBALL', 'CONCERT', 'THEATER', 'OTHER')),
    attributes            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    poster_url            text,
    status                text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ENDED', 'CANCELLED')),
    max_tickets_per_order int         NOT NULL DEFAULT 10 CHECK (max_tickets_per_order > 0),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    published_at          timestamptz
);
CREATE INDEX event_status_idx ON event (status);

-- Chỉ lưu DRAFT, SCHEDULED, CANCELLED. ON_SALE, SALES_CLOSED, FINISHED tính khi đọc từ các mốc giờ.
CREATE TABLE showtime (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    event_id       uuid        NOT NULL REFERENCES event (id),
    hall_id        uuid        NOT NULL REFERENCES hall (id),
    seat_layout_id uuid        NOT NULL REFERENCES seat_layout (id),
    starts_at      timestamptz NOT NULL,
    ends_at        timestamptz NOT NULL,
    sale_opens_at  timestamptz NOT NULL,
    sale_closes_at timestamptz NOT NULL,
    status         text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SCHEDULED', 'CANCELLED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (sale_closes_at > sale_opens_at),
    CHECK (sale_closes_at <= ends_at)
);
CREATE INDEX showtime_event_idx ON showtime (event_id, starts_at);

CREATE TABLE price_tier (
    id       uuid   PRIMARY KEY DEFAULT uuidv7(),
    event_id uuid   NOT NULL REFERENCES event (id),
    name     text   NOT NULL,
    price    bigint NOT NULL CHECK (price >= 0),
    color    text,
    UNIQUE (event_id, name)
);

CREATE TABLE zone_price (
    event_id      uuid NOT NULL REFERENCES event (id),
    zone_id       uuid NOT NULL REFERENCES zone (id),
    price_tier_id uuid NOT NULL REFERENCES price_tier (id),
    PRIMARY KEY (event_id, zone_id)
);
