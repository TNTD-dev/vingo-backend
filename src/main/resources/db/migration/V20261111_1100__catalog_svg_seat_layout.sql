ALTER TABLE venue
    ADD COLUMN district text,
    ADD COLUMN lat numeric,
    ADD COLUMN lng numeric,
    ADD CONSTRAINT venue_coordinates_check CHECK (
        (lat IS NULL AND lng IS NULL)
        OR (
            lat IS NOT NULL AND lng IS NOT NULL
            AND lat BETWEEN -90 AND 90
            AND lng BETWEEN -180 AND 180
        )
    );

ALTER TABLE seat_layout
    ADD COLUMN version bigint GENERATED ALWAYS AS IDENTITY,
    ADD CONSTRAINT seat_layout_hall_version_uk UNIQUE (hall_id, version);

CREATE TABLE seat_layout_svg (
    seat_layout_id uuid PRIMARY KEY REFERENCES seat_layout (id),
    schema_version integer NOT NULL CHECK (schema_version > 0),
    svg_content text NOT NULL CHECK (length(svg_content) > 0),
    view_box_x numeric NOT NULL,
    view_box_y numeric NOT NULL,
    view_box_width numeric NOT NULL CHECK (view_box_width > 0),
    view_box_height numeric NOT NULL CHECK (view_box_height > 0)
);

ALTER TABLE zone
    ADD COLUMN code text,
    ADD COLUMN stand_code text,
    ADD COLUMN level integer CHECK (level > 0),
    ADD COLUMN svg_id text,
    ADD COLUMN content_svg_id text,
    ADD COLUMN pitch_side_svg_id text,
    ADD COLUMN bounds_x numeric,
    ADD COLUMN bounds_y numeric,
    ADD COLUMN bounds_width numeric,
    ADD COLUMN bounds_height numeric;

UPDATE zone
SET code = name
WHERE code IS NULL;

ALTER TABLE zone
    ADD CONSTRAINT zone_layout_code_uk UNIQUE (seat_layout_id, code),
    ADD CONSTRAINT zone_layout_svg_id_uk UNIQUE (seat_layout_id, svg_id),
    ADD CONSTRAINT zone_svg_bounds_check CHECK (
        (
            svg_id IS NULL
            AND num_nonnulls(bounds_x, bounds_y, bounds_width, bounds_height) = 0
        )
        OR (
            svg_id IS NOT NULL
            AND code IS NOT NULL
            AND num_nonnulls(bounds_x, bounds_y, bounds_width, bounds_height) = 4
            AND bounds_width > 0
            AND bounds_height > 0
        )
    );

ALTER TABLE seat
    ALTER COLUMN x TYPE numeric,
    ALTER COLUMN y TYPE numeric;