-- Hàng đợi outbox: ghi cùng transaction nghiệp vụ, outbox_sender gửi sau.
CREATE TABLE outbox_message (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    channel    text        NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    recipient  text        NOT NULL,
    template   text        NOT NULL,
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status     text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts   int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    send_after timestamptz NOT NULL DEFAULT now(),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at    timestamptz
);
CREATE INDEX outbox_message_pending_idx ON outbox_message (send_after) WHERE status = 'PENDING';
