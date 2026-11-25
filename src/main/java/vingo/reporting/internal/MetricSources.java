package vingo.reporting.internal;

/** SELECT trên bảng gốc, không tạo object DB hoặc dữ liệu tổng hợp. */
final class MetricSources {
    private MetricSources() {}

    static final String SALES = """
            SELECT o.id AS order_id, s.event_id, o.showtime_id, se.zone_id,
                   ttp.ticket_type_id AS price_tier_id,
                   1 AS quantity, os.unit_price, o.paid_at
            FROM ticket_order o
            JOIN order_seat os ON os.order_id = o.id
            JOIN seat se ON se.id = os.seat_id
            JOIN ticket_type_price ttp ON ttp.id = os.ticket_type_price_id
            JOIN showtime s ON s.id = o.showtime_id
            WHERE o.status = 'PAID'
            UNION ALL
            SELECT o.id AS order_id, s.event_id, o.showtime_id, ost.zone_id,
                   ttp.ticket_type_id AS price_tier_id,
                   ost.quantity::integer AS quantity, ost.unit_price, o.paid_at
            FROM ticket_order o
            JOIN order_standing ost ON ost.order_id = o.id
            JOIN ticket_type_price ttp ON ttp.id = ost.ticket_type_price_id
            JOIN showtime s ON s.id = o.showtime_id
            WHERE o.status = 'PAID'
            """;

    static final String INVENTORY = """
            SELECT s.event_id, s.id AS showtime_id, si.zone_id, ez.ticket_type_id AS price_tier_id, s.starts_at,
                   count(*)::bigint AS capacity,
                   count(*) FILTER (WHERE si.status = 'SOLD')::bigint AS sold
            FROM seat_inventory si
            JOIN showtime s ON s.id = si.showtime_id AND s.cancelled_at IS NULL
            JOIN event_zone ez ON ez.event_id = s.event_id AND ez.zone_id = si.zone_id
            GROUP BY s.event_id, s.id, si.zone_id, ez.ticket_type_id, s.starts_at
            UNION ALL
            SELECT s.event_id, s.id AS showtime_id, z.zone_id, ez.ticket_type_id AS price_tier_id, s.starts_at,
                   z.capacity::bigint, z.sold::bigint
            FROM standing_inventory z
            JOIN showtime s ON s.id = z.showtime_id AND s.cancelled_at IS NULL
            JOIN event_zone ez ON ez.event_id = s.event_id AND ez.zone_id = z.zone_id
            """;

    static final String CHECKINS = """
            SELECT t.id AS ticket_id, s.event_id, o.showtime_id,
                   COALESCE(t.zone_id, se.zone_id) AS zone_id,
                   ttp.ticket_type_id AS price_tier_id,
                   min(c.scanned_at) AS checked_in_at
            FROM ticket t
            JOIN ticket_order o ON o.id = t.order_id
            JOIN showtime s ON s.id = o.showtime_id
            LEFT JOIN seat se ON se.id = t.seat_id
            LEFT JOIN order_seat os ON os.order_id = t.order_id AND os.seat_id = t.seat_id
            LEFT JOIN order_standing ost ON ost.order_id = t.order_id AND ost.zone_id = t.zone_id
            JOIN ticket_type_price ttp ON ttp.id = COALESCE(os.ticket_type_price_id, ost.ticket_type_price_id)
            JOIN ticket_scan c ON c.ticket_id = t.id AND c.result = 'OK'
            WHERE t.status = 'USED' AND o.status = 'PAID'
            GROUP BY t.id, s.event_id, o.showtime_id, COALESCE(t.zone_id, se.zone_id), ttp.ticket_type_id
            """;
}
