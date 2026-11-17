ALTER TABLE event ADD COLUMN venue_id uuid;
ALTER TABLE event ADD COLUMN seat_layout_id uuid;
ALTER TABLE event ADD CONSTRAINT event_layout_complete_chk
    CHECK ((venue_id IS NULL) = (seat_layout_id IS NULL));
ALTER TABLE event ADD CONSTRAINT event_layout_venue_fk FOREIGN KEY (seat_layout_id, venue_id)
    REFERENCES seat_layout(id, venue_id);

-- Fill only unambiguous, compatible v2 events. Keep historic mixed layouts intact.
WITH candidates AS (
    SELECT s.event_id, (array_agg(DISTINCT s.seat_layout_id))[1] AS layout_id
    FROM showtime s
    WHERE s.status <> 'CANCELLED'
    GROUP BY s.event_id HAVING count(DISTINCT s.seat_layout_id) = 1
)
UPDATE event e SET venue_id = l.venue_id, seat_layout_id = l.id
FROM candidates c JOIN seat_layout l ON l.id = c.layout_id
WHERE e.id = c.event_id AND l.status = 'PUBLISHED' AND l.layout_schema_version = 2
  AND l.layout_type = e.category;

ALTER TABLE zone_price ADD COLUMN display_name text
    CHECK (display_name IS NULL OR length(btrim(display_name)) BETWEEN 1 AND 200);

-- Amount already lives on the order item. Preserve names at the same point in time.
ALTER TABLE order_item ADD COLUMN zone_name_snapshot text;
ALTER TABLE order_item ADD COLUMN tier_name_snapshot text;
UPDATE order_item i SET zone_name_snapshot = z.name, tier_name_snapshot = p.name
FROM zone z, price_tier p WHERE z.id = i.zone_id AND p.id = i.price_tier_id;

CREATE FUNCTION capture_order_item_names() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT COALESCE(zp.display_name, z.name) INTO NEW.zone_name_snapshot
    FROM orders o JOIN showtime s ON s.id = o.showtime_id
    JOIN zone z ON z.id = NEW.zone_id
    LEFT JOIN zone_price zp ON zp.event_id = s.event_id AND zp.zone_id = z.id
    WHERE o.id = NEW.order_id;
    SELECT name INTO NEW.tier_name_snapshot FROM price_tier WHERE id = NEW.price_tier_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_item_capture_names BEFORE INSERT ON order_item
    FOR EACH ROW EXECUTE FUNCTION capture_order_item_names();

CREATE FUNCTION guard_selected_event_showtime() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected uuid;
BEGIN
    IF NEW.status = 'CANCELLED' THEN RETURN NEW; END IF;
    SELECT seat_layout_id INTO selected FROM event WHERE id = NEW.event_id FOR UPDATE;
    IF selected IS NOT NULL AND selected <> NEW.seat_layout_id THEN
        RAISE EXCEPTION 'EVENT_LAYOUT_MISMATCH' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER event_showtime_layout_guard BEFORE INSERT OR UPDATE OF event_id, seat_layout_id ON showtime
    FOR EACH ROW EXECUTE FUNCTION guard_selected_event_showtime();
