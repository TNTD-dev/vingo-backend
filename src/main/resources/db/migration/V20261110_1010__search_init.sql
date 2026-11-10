-- Tìm sự kiện: full-text + trigram trên tiêu đề đã bỏ dấu, gõ không dấu vẫn ra.
-- unaccent() không IMMUTABLE nên bọc lại để dùng được trong index.
CREATE FUNCTION immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE INDEX event_title_fts_idx ON event
    USING gin (to_tsvector('simple', immutable_unaccent(coalesce(title, '') || ' ' || coalesce(description, ''))));
CREATE INDEX event_title_trgm_idx ON event
    USING gin (immutable_unaccent(lower(title)) gin_trgm_ops);
