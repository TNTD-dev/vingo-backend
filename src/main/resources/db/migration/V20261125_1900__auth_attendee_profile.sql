CREATE TABLE attendee_profile (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES user_account,
    full_name  text        NOT NULL CHECK (full_name = btrim(full_name) AND length(full_name) BETWEEN 1 AND 120),
    email      text        CHECK (email = lower(btrim(email)) AND length(email) <= 254),
    phone      text        CHECK (phone ~ '^\+[1-9][0-9]{8,14}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX attendee_profile_user_id_idx ON attendee_profile (user_id, id);
