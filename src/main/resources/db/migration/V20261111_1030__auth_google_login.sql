ALTER TABLE users ADD COLUMN avatar_url text;

CREATE TABLE oauth_login_request (
    state_hash   text        PRIMARY KEY,
    browser_hash text        NOT NULL,
    request_json text        NOT NULL,
    expires_at   timestamptz NOT NULL
);
CREATE INDEX oauth_login_request_expiry_idx ON oauth_login_request (expires_at);
