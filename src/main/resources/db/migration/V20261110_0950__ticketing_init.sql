-- Ticketing: vé và lượt quét. Khu đứng có nhiều vé cho một order_item, phân biệt bằng seq.
CREATE TABLE ticket (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    order_item_id uuid        NOT NULL REFERENCES order_item (id),
    seq           int         NOT NULL DEFAULT 1 CHECK (seq > 0),
    showtime_id   uuid        NOT NULL REFERENCES showtime (id),
    zone_id       uuid        NOT NULL REFERENCES zone (id),
    seat_id       uuid        REFERENCES seat (id),
    code          text        NOT NULL UNIQUE,
    qr_token      text        NOT NULL UNIQUE,
    status        text        NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED', 'USED', 'VOID')),
    issued_at     timestamptz NOT NULL DEFAULT now(),
    used_at       timestamptz,
    voided_at     timestamptz,
    UNIQUE (order_item_id, seq),
    CHECK (status <> 'USED' OR used_at IS NOT NULL),
    CHECK (status <> 'VOID' OR voided_at IS NOT NULL)
);
-- Chốt chặn cuối chống bán trùng ghế.
CREATE UNIQUE INDEX ticket_seat_uk ON ticket (showtime_id, seat_id) WHERE seat_id IS NOT NULL AND status <> 'VOID';

CREATE TABLE checkin (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    ticket_id   uuid        NOT NULL REFERENCES ticket (id),
    showtime_id uuid        NOT NULL REFERENCES showtime (id),
    scanned_by  uuid        NOT NULL REFERENCES users (id),
    gate        text,
    result      text        NOT NULL CHECK (result IN ('OK', 'ALREADY_USED', 'TICKET_VOID', 'WRONG_SHOWTIME')),
    scanned_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX checkin_showtime_idx ON checkin (showtime_id, scanned_at DESC);
CREATE INDEX checkin_ticket_idx ON checkin (ticket_id);
