ALTER TABLE event
    ADD COLUMN poster_mobile_url text,
    ADD COLUMN poster_desktop_url text,
    ADD COLUMN age_limit integer,
    ADD CONSTRAINT event_age_limit_check CHECK (
        age_limit IS NULL OR age_limit >= 0
    );

UPDATE event
SET poster_mobile_url = poster_url,
    poster_desktop_url = poster_url
WHERE poster_url IS NOT NULL;