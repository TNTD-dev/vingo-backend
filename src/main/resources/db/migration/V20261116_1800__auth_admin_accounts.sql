-- Khóa quản trị độc lập với khóa tạm do đăng nhập sai; phiên bản quyền thu hồi JWT cũ.
ALTER TABLE users
    ADD COLUMN admin_locked boolean NOT NULL DEFAULT false,
    ADD COLUMN auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0);

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'VOID_TICKET', 'REISSUE_QR', 'RESEND_TICKET', 'RESOLVE_REVIEW',
    'BLOCK_SEATS', 'CANCEL_EVENT', 'CANCEL_SHOWTIME', 'UPDATE_ACCOUNT'
));
