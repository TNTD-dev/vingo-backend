CREATE TABLE catalog_seat_block_log (
 id uuid PRIMARY KEY DEFAULT uuidv7(),
 showtime_id uuid NOT NULL REFERENCES showtime(id),
 action text NOT NULL CHECK (action IN ('BLOCK','UNBLOCK')),
 seat_ids uuid[] NOT NULL CHECK (cardinality(seat_ids) > 0),
 reason text,
 occurred_at timestamptz NOT NULL,
 CHECK ((action='BLOCK' AND reason IS NOT NULL AND reason ~ '[^[:space:]]' AND char_length(reason)<=1000)
 OR (action='UNBLOCK' AND reason IS NULL))
);
CREATE INDEX catalog_seat_block_log_showtime_idx ON catalog_seat_block_log(showtime_id, occurred_at DESC);
