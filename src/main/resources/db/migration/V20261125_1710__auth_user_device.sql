CREATE TABLE user_device (
    user_id      uuid        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    device_hash  text        NOT NULL CHECK (device_hash ~ '^[0-9a-f]{64}$'),
    created_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, device_hash)
);

CREATE INDEX user_device_last_seen_idx ON user_device (last_seen_at);
