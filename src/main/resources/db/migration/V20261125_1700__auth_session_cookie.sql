-- Old rows authenticated refresh requests only. Never accept them as full sessions.
DELETE FROM login_session;

ALTER TABLE login_session
    ADD COLUMN user_agent text CHECK (char_length(user_agent) <= 512),
    ADD COLUMN last_ip inet,
    ADD COLUMN last_seen_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE user_account DROP COLUMN auth_version;

COMMENT ON COLUMN user_account.admin_locked IS 'Administrative lock blocks login and use of existing sessions';
COMMENT ON TABLE login_session IS 'One revocable login session per device';
COMMENT ON COLUMN login_session.token_hash IS 'SHA-256 hex of the opaque session token';
COMMENT ON COLUMN login_session.last_seen_at IS 'Last activity for the device list, sampled every five minutes';
