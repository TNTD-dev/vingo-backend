-- Giữ audit nghiệp vụ cũ và bổ sung nhật ký cho mọi chức năng quản trị.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'VOID_TICKET', 'REISSUE_QR', 'RESEND_TICKET', 'RESOLVE_REVIEW',
    'BLOCK_SEATS', 'CANCEL_EVENT', 'CANCEL_SHOWTIME', 'UPDATE_ACCOUNT', 'ADMIN_ACTIVITY'
));
CREATE INDEX audit_log_actor_at_idx ON audit_log (actor_id, at DESC, id DESC);
