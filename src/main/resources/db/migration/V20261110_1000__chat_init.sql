-- Hội thoại và agent. Agent runtime stateless, mọi trạng thái lưu ở đây.
CREATE TABLE conversation (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id         uuid        NOT NULL REFERENCES users (id),
    role            text        NOT NULL CHECK (role IN ('BUYER', 'ADMIN')),
    auto_confirm    text[]      NOT NULL DEFAULT '{}',
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_message_at timestamptz
);
CREATE INDEX conversation_user_idx ON conversation (user_id, created_at DESC);

CREATE TABLE message (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    conversation_id uuid        NOT NULL REFERENCES conversation (id),
    sender          text        NOT NULL CHECK (sender IN ('USER', 'AGENT', 'TOOL')),
    content         text,
    blocks          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX message_conversation_idx ON message (conversation_id, id);

-- id chính là action_id trong API /chat/actions/{action_id}.
CREATE TABLE pending_action (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    conversation_id uuid        NOT NULL REFERENCES conversation (id),
    message_id      uuid        UNIQUE REFERENCES message (id),
    tool            text        NOT NULL,
    args            jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    resolved_at     timestamptz,
    CHECK ((status = 'PENDING') = (resolved_at IS NULL))
);
CREATE INDEX pending_action_conversation_idx ON pending_action (conversation_id) WHERE status = 'PENDING';
