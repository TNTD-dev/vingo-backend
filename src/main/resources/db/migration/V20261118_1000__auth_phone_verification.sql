-- Số chưa xác minh không được chiếm quyền sử dụng số của chủ thật.
ALTER TABLE users
    ADD COLUMN phone_verified_at timestamptz,
    ADD CONSTRAINT users_verified_phone_present_chk
        CHECK (phone_verified_at IS NULL OR phone IS NOT NULL);

DROP INDEX users_phone_uk;
CREATE UNIQUE INDEX users_verified_phone_uk ON users (phone)
    WHERE phone_verified_at IS NOT NULL;

ALTER TABLE otp_session DROP CONSTRAINT IF EXISTS otp_session_purpose_check;
ALTER TABLE otp_session
    ADD CONSTRAINT otp_session_purpose_check CHECK (purpose IN ('VERIFY', 'RESET', 'PHONE')),
    ADD COLUMN phone text,
    ADD CONSTRAINT otp_session_phone_purpose_chk CHECK ((purpose = 'PHONE') = (phone IS NOT NULL));

-- Lưu cả lần gửi nhà cung cấp báo lỗi để giới hạn chi phí không phụ thuộc Redis.
CREATE TABLE phone_otp_send (
    id      uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid        NOT NULL REFERENCES users (id),
    phone   text        NOT NULL,
    sent_at timestamptz NOT NULL
);
CREATE INDEX phone_otp_send_user_sent_idx ON phone_otp_send (user_id, sent_at DESC);
CREATE INDEX phone_otp_send_phone_sent_idx ON phone_otp_send (phone, sent_at DESC);
