-- Bổ sung trường theo dõi gửi lại, constraint và index cho otp_session.
ALTER TABLE otp_session
    ADD COLUMN last_sent_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT otp_session_expiry_chk CHECK (expires_at > created_at);

CREATE UNIQUE INDEX otp_session_active_user_purpose_uk
    ON otp_session (user_id, purpose)
    WHERE consumed_at IS NULL;

CREATE INDEX otp_session_expires_idx
    ON otp_session (expires_at)
    WHERE consumed_at IS NULL;
