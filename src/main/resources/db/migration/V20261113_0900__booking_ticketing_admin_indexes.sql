CREATE INDEX orders_showtime_idx ON orders (showtime_id, created_at DESC);
CREATE INDEX orders_buyer_email_idx ON orders (lower(buyer_email));
CREATE INDEX orders_buyer_phone_idx ON orders (buyer_phone);
CREATE INDEX ticket_showtime_idx ON ticket (showtime_id, issued_at DESC);
