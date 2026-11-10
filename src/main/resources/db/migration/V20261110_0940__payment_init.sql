-- Payment: mỗi lần thử thanh toán là một intent; mọi IPN/querydr ghi vào payment_transaction (raw).
CREATE TABLE payment_intent (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    order_id        uuid        NOT NULL REFERENCES orders (id),
    txn_ref         text        NOT NULL UNIQUE,
    amount          bigint      NOT NULL CHECK (amount > 0),
    status          text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    payment_url     text,
    idempotency_key text        UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL
);
-- Mỗi đơn tối đa 1 intent OPEN và 1 intent SUCCEEDED.
CREATE UNIQUE INDEX payment_intent_one_open_uk ON payment_intent (order_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX payment_intent_one_succeeded_uk ON payment_intent (order_id) WHERE status = 'SUCCEEDED';
CREATE INDEX payment_intent_open_idx ON payment_intent (created_at) WHERE status = 'OPEN';

CREATE TABLE payment_transaction (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    payment_intent_id uuid        NOT NULL REFERENCES payment_intent (id),
    source            text        NOT NULL CHECK (source IN ('IPN', 'QUERY')),
    result            text        NOT NULL CHECK (result IN ('SUCCESS', 'FAILED')),
    response_code     text,
    gateway_txn_no    text,
    amount            bigint,
    raw               jsonb       NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_transaction_intent_idx ON payment_transaction (payment_intent_id);
-- Chốt chặn cuối: một intent chỉ có một giao dịch thành công.
CREATE UNIQUE INDEX payment_transaction_one_success_uk ON payment_transaction (payment_intent_id) WHERE result = 'SUCCESS';
