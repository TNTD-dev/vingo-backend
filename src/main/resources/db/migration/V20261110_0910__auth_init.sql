-- Danh tính: user, phiên, OTP, lần đăng nhập, hồ sơ người mua.
-- Chỉ lưu hash của mật khẩu (Argon2), OTP và refresh token.
CREATE TABLE users (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    email         text        NOT NULL,
    phone         text,
    full_name     text        NOT NULL,
    password_hash text,
    google_sub    text,
    role          text        NOT NULL DEFAULT 'BUYER' CHECK (role IN ('BUYER', 'ADMIN')),
    status        text        NOT NULL DEFAULT 'UNVERIFIED' CHECK (status IN ('UNVERIFIED', 'ACTIVE', 'LOCKED', 'DELETED')),
    locked_until  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL OR status = 'DELETED')
);
CREATE UNIQUE INDEX users_email_uk ON users (lower(email));
CREATE UNIQUE INDEX users_phone_uk ON users (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX users_google_sub_uk ON users (google_sub) WHERE google_sub IS NOT NULL;

CREATE TABLE sessions (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id            uuid        NOT NULL REFERENCES users (id),
    refresh_token_hash text        NOT NULL UNIQUE,
    device             text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz NOT NULL,
    revoked_at         timestamptz
);
CREATE INDEX sessions_user_idx ON sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE otp_session (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid        NOT NULL REFERENCES users (id),
    purpose     text        NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    channel     text        NOT NULL DEFAULT 'EMAIL' CHECK (channel IN ('EMAIL', 'SMS')),
    otp_hash    text        NOT NULL,
    attempts    int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX otp_session_user_idx ON otp_session (user_id, purpose);

CREATE TABLE login_attempt (
    id      uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid        NOT NULL REFERENCES users (id),
    success boolean     NOT NULL,
    at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_attempt_user_at_idx ON login_attempt (user_id, at DESC);

CREATE TABLE buyer_profile (
    user_id    uuid        PRIMARY KEY REFERENCES users (id),
    full_name  text        NOT NULL,
    phone      text        NOT NULL,
    email      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
