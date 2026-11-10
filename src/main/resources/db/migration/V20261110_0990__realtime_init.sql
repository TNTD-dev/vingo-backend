-- Sự kiện realtime: ghi cùng transaction với thay đổi dữ liệu, NOTIFY để api đẩy qua SSE.
-- id là uuidv7 (tăng theo thời gian) dùng làm SSE id; reconnect với Last-Event-ID nhận tiếp trong 5 phút.
CREATE TABLE realtime_event (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    type       text        NOT NULL CHECK (type IN ('order.held', 'order.paid', 'order.expired', 'order.needs_review',
                                                    'payment.failed', 'ticket.issued',
                                                    'chat.delta', 'chat.block', 'chat.done')),
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX realtime_event_user_idx ON realtime_event (user_id, id);
CREATE INDEX realtime_event_created_idx ON realtime_event (created_at);
