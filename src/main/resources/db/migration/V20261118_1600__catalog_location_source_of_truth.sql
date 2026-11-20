-- Giữ nguyên địa chỉ cũ, không suy đoán tọa độ còn thiếu.
UPDATE venue SET location = jsonb_strip_nulls(jsonb_build_object(
    'display_address', address, 'latitude', lat, 'longitude', lng,
    'city_code', city, 'source', 'LEGACY',
    'administrative_area', CASE WHEN district IS NOT NULL THEN
        jsonb_build_array(jsonb_build_object('name', district, 'level', 'DISTRICT')) ELSE '[]'::jsonb END
)) || COALESCE(location, '{}'::jsonb);

CREATE FUNCTION venue_location_city(jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE(NULLIF($1->>'city_code',''),
        (SELECT COALESCE(NULLIF(a->>'code',''),a->>'name')
         FROM jsonb_array_elements(COALESCE($1->'administrative_area','[]'::jsonb)) a
         WHERE a->>'level' IN ('PROVINCE','CITY')
         ORDER BY CASE a->>'level' WHEN 'PROVINCE' THEN 0 ELSE 1 END LIMIT 1))
$$;

DROP INDEX IF EXISTS venue_search_fts_idx;
ALTER TABLE venue DROP COLUMN address, DROP COLUMN city, DROP COLUMN district,
    DROP COLUMN lat, DROP COLUMN lng;
ALTER TABLE venue ADD COLUMN updated_by uuid REFERENCES users(id);
ALTER TABLE venue ADD CONSTRAINT venue_location_object_chk
    CHECK (location IS NULL OR jsonb_typeof(location) = 'object');
ALTER TABLE venue ADD CONSTRAINT venue_location_coordinates_chk CHECK (
    (location->>'latitude' IS NULL AND location->>'longitude' IS NULL)
    OR (location->>'latitude' IS NOT NULL AND location->>'longitude' IS NOT NULL
        AND (location->>'latitude')::numeric BETWEEN -90 AND 90
        AND (location->>'longitude')::numeric BETWEEN -180 AND 180));
CREATE INDEX venue_location_city_idx ON venue (venue_location_city(location)) WHERE deleted_at IS NULL;
CREATE INDEX venue_search_fts_idx ON venue USING gin
    (to_tsvector('simple', immutable_unaccent(coalesce(name,'') || ' ' || immutable_city_text(venue_location_city(location)))));
