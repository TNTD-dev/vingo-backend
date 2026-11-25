-- Flyway runs this migration in one transaction. Block writes until preflight and DDL finish.
LOCK TABLE user_account, otp_verification IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    google_email_unverified bigint;
    admin_without_password bigint;
    deleted_personal_data bigint;
BEGIN
    SELECT count(*) FILTER (WHERE google_sub IS NOT NULL AND email_verified_at IS NULL),
           count(*) FILTER (WHERE role = 'ADMIN' AND password_hash IS NULL AND deleted_at IS NULL),
           count(*) FILTER (WHERE deleted_at IS NOT NULL AND
               (password_hash IS NOT NULL OR google_sub IS NOT NULL OR phone IS NOT NULL OR avatar_url IS NOT NULL))
    INTO google_email_unverified, admin_without_password, deleted_personal_data
    FROM user_account;

    IF google_email_unverified > 0 OR admin_without_password > 0 OR deleted_personal_data > 0 THEN
        RAISE EXCEPTION 'Identity cleanup blocked: google_email_unverified=%, admin_without_password=%, deleted_personal_data=%. User data was not changed.',
            google_email_unverified, admin_without_password, deleted_personal_data;
    END IF;
END $$;

DROP INDEX user_account_verified_phone_uk;
-- PostgreSQL also drops the CHECK that depends on the removed column; keep the phone format CHECK.
ALTER TABLE user_account DROP COLUMN phone_verified_at;

DELETE FROM otp_verification WHERE purpose = 'PHONE';
DROP INDEX otp_verification_phone_idx;
-- Also removes the phone format CHECK and the PHONE-purpose/phone-presence CHECK.
ALTER TABLE otp_verification DROP COLUMN phone;

CREATE TYPE otp_purpose_email AS ENUM ('VERIFY', 'RESET');
ALTER TABLE otp_verification ALTER COLUMN purpose TYPE otp_purpose_email USING purpose::text::otp_purpose_email;
DROP TYPE otp_purpose;
ALTER TYPE otp_purpose_email RENAME TO otp_purpose;

ALTER TABLE otp_verification ADD COLUMN target_email text;
UPDATE otp_verification o SET target_email = u.email FROM user_account u WHERE u.id = o.user_id;
ALTER TABLE otp_verification ALTER COLUMN target_email SET NOT NULL;
-- Keep attempts >= 0: AUTH_OTP_MAX_ATTEMPTS is configurable, so there is no fixed upper bound of 5.

ALTER TABLE user_account
    ADD CONSTRAINT user_account_google_email_verified_check
        CHECK (google_sub IS NULL OR email_verified_at IS NOT NULL),
    ADD CONSTRAINT user_account_admin_password_check
        CHECK (role <> 'ADMIN' OR password_hash IS NOT NULL OR deleted_at IS NOT NULL),
    ADD CONSTRAINT user_account_deleted_personal_data_check
        CHECK (deleted_at IS NULL OR
            (password_hash IS NULL AND google_sub IS NULL AND phone IS NULL AND avatar_url IS NULL));

COMMENT ON COLUMN user_account.phone IS 'Contact phone; multiple accounts may share the same number';
COMMENT ON TABLE otp_verification IS 'One row per email OTP send attempt; only the latest delivered OTP per user and purpose is valid';
COMMENT ON COLUMN otp_verification.purpose IS 'VERIFY (verify account email) | RESET (reset password)';
COMMENT ON COLUMN otp_verification.target_email IS 'Email address at issuance; must still match the account email when the OTP is used';
