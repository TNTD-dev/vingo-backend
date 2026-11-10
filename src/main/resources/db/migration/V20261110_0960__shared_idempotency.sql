-- Idempotency-Key cho: tạo hold, tạo payment, resolve-review. Cùng key + cùng body → trả response cũ.
CREATE TABLE idempotency_key (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id         uuid        NOT NULL REFERENCES users (id),
    endpoint        text        NOT NULL,
    key             text        NOT NULL,
    request_hash    text        NOT NULL,
    response_status int,
    response        jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, endpoint, key)
);
CREATE INDEX idempotency_key_created_idx ON idempotency_key (created_at);
