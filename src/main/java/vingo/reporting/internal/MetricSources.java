package vingo.reporting.internal;

/** SELECT trên bảng gốc, không tạo object DB hoặc dữ liệu tổng hợp. */
final class MetricSources {
    private MetricSources() {}
    static final String SALES = """
SELECT o.id AS order_id, s.event_id, o.showtime_id, i.zone_id, i.price_tier_id,
       i.quantity, i.unit_price, o.paid_at
FROM orders o JOIN order_item i ON i.order_id = o.id
JOIN showtime s ON s.id = o.showtime_id
WHERE o.status = 'PAID'
            """;
    static final String INVENTORY = """
SELECT s.event_id, s.id AS showtime_id, se.zone_id, zp.price_tier_id, s.starts_at,
       count(*)::bigint AS capacity,
       count(*) FILTER (WHERE ss.status = 'SOLD')::bigint AS sold
FROM showtime_seat ss JOIN showtime s ON s.id = ss.showtime_id
JOIN seat se ON se.id = ss.seat_id
JOIN zone_price zp ON zp.event_id = s.event_id AND zp.zone_id = se.zone_id
GROUP BY s.event_id, s.id, se.zone_id, zp.price_tier_id, s.starts_at
UNION ALL
SELECT s.event_id, s.id, z.zone_id, zp.price_tier_id, s.starts_at, z.capacity::bigint, z.sold::bigint
FROM showtime_zone_stock z JOIN showtime s ON s.id = z.showtime_id
JOIN zone_price zp ON zp.event_id = s.event_id AND zp.zone_id = z.zone_id
            """;
    static final String CHECKINS = """
SELECT t.id AS ticket_id, s.event_id, o.showtime_id, i.zone_id, i.price_tier_id,
       min(c.scanned_at) AS checked_in_at
FROM ticket t JOIN order_item i ON i.id = t.order_item_id
JOIN orders o ON o.id = i.order_id JOIN showtime s ON s.id = o.showtime_id
JOIN checkin c ON c.ticket_id = t.id AND c.result = 'OK'
WHERE t.status = 'USED' AND o.status = 'PAID'
GROUP BY t.id, s.event_id, o.showtime_id, i.zone_id, i.price_tier_id
            """;
}
