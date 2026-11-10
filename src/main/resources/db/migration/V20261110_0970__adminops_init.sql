-- Nhật ký thao tác của admin (kể cả thao tác Admin Copilot làm sau khi được duyệt).
CREATE TABLE audit_log (
    id        uuid        PRIMARY KEY DEFAULT uuidv7(),
    actor_id  uuid        NOT NULL REFERENCES users (id),
    action    text        NOT NULL CHECK (action IN ('VOID_TICKET', 'REISSUE_QR', 'RESEND_TICKET', 'RESOLVE_REVIEW',
                                                      'BLOCK_SEATS', 'CANCEL_EVENT', 'CANCEL_SHOWTIME')),
    entity    text        NOT NULL,
    entity_id uuid        NOT NULL,
    reason    text,
    via_agent boolean     NOT NULL DEFAULT false,
    at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_at_idx ON audit_log (at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity, entity_id);
