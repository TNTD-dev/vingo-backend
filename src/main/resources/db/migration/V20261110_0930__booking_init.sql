-- Booking: tồn kho theo suất, đơn hàng, lịch sử trạng thái.
CREATE TABLE orders (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    code          char(6)     NOT NULL UNIQUE,
    user_id       uuid        NOT NULL REFERENCES users (id),
    showtime_id   uuid        NOT NULL REFERENCES showtime (id),
    status        text        NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'NEEDS_REVIEW', 'REFUNDED')),
    total_amount  bigint      NOT NULL CHECK (total_amount >= 0),
    expires_at    timestamptz NOT NULL,
    buyer_name    text,
    buyer_email   text,
    buyer_phone   text,
    paid_at       timestamptz,
    review_reason text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
    CHECK (status <> 'NEEDS_REVIEW' OR review_reason IS NOT NULL)
);
-- Mỗi user tối đa 1 đơn PENDING cho mỗi suất.
CREATE UNIQUE INDEX orders_one_pending_per_showtime_uk ON orders (user_id, showtime_id) WHERE status = 'PENDING';
CREATE INDEX orders_pending_expiry_idx ON orders (expires_at) WHERE status = 'PENDING';
CREATE INDEX orders_user_idx ON orders (user_id, created_at DESC);
CREATE INDEX orders_status_idx ON orders (status, created_at DESC);

-- Ghế trống = AVAILABLE hoặc (HELD và held_until < now()). Không dùng Redis để giữ ghế.
CREATE TABLE showtime_seat (
    showtime_id uuid        NOT NULL REFERENCES showtime (id),
    seat_id     uuid        NOT NULL REFERENCES seat (id),
    status      text        NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD', 'BLOCKED')),
    order_id    uuid        REFERENCES orders (id),
    held_until  timestamptz,
    version     int         NOT NULL DEFAULT 0,
    PRIMARY KEY (showtime_id, seat_id),
    CHECK (status NOT IN ('HELD', 'SOLD') OR order_id IS NOT NULL),
    CHECK (status <> 'HELD' OR held_until IS NOT NULL)
);
CREATE INDEX showtime_seat_order_idx ON showtime_seat (order_id) WHERE order_id IS NOT NULL;

-- Khu đứng: bộ đếm, giữ bằng UPDATE có điều kiện capacity - sold - held >= n.
CREATE TABLE showtime_zone_stock (
    showtime_id uuid NOT NULL REFERENCES showtime (id),
    zone_id     uuid NOT NULL REFERENCES zone (id),
    capacity    int  NOT NULL CHECK (capacity >= 0),
    sold        int  NOT NULL DEFAULT 0 CHECK (sold >= 0),
    held        int  NOT NULL DEFAULT 0 CHECK (held >= 0),
    PRIMARY KEY (showtime_id, zone_id),
    CHECK (sold + held <= capacity)
);

-- Ghế ngồi: 1 dòng mỗi ghế (quantity = 1). Khu đứng: 1 dòng mỗi khu, quantity = n.
CREATE TABLE order_item (
    id            uuid   PRIMARY KEY DEFAULT uuidv7(),
    order_id      uuid   NOT NULL REFERENCES orders (id),
    zone_id       uuid   NOT NULL REFERENCES zone (id),
    seat_id       uuid   REFERENCES seat (id),
    price_tier_id uuid   NOT NULL REFERENCES price_tier (id),
    quantity      int    NOT NULL CHECK (quantity > 0),
    unit_price    bigint NOT NULL CHECK (unit_price >= 0),
    CHECK (seat_id IS NULL OR quantity = 1)
);
CREATE INDEX order_item_order_idx ON order_item (order_id);
CREATE UNIQUE INDEX order_item_seat_uk ON order_item (order_id, seat_id) WHERE seat_id IS NOT NULL;

-- actor_id NULL nghĩa là SYSTEM (job nền, IPN).
CREATE TABLE order_status_history (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    order_id    uuid        NOT NULL REFERENCES orders (id),
    from_status text,
    to_status   text        NOT NULL,
    actor_id    uuid        REFERENCES users (id),
    reason      text,
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, at);
