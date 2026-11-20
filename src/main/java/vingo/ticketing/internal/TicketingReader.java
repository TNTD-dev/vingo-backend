package vingo.ticketing.internal;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import vingo.api.model.CheckinResult;
import vingo.api.model.CityCode;
import vingo.api.model.EventCategory;
import vingo.api.model.EventSummary;
import vingo.api.model.Showtime;
import vingo.api.model.ShowtimeStatus;
import vingo.api.model.Ticket;
import vingo.api.model.TicketStatus;
import vingo.api.model.Venue;

/** Đọc chi tiết vé và lượt quét theo schema v2; QR được tính động bằng QrCodec. */
@Repository
public class TicketingReader {

    private final JdbcClient jdbc;
    private final QrCodec qrCodec;

    public TicketingReader(JdbcClient jdbc, QrCodec qrCodec) {
        this.jdbc = jdbc;
        this.qrCodec = qrCodec;
    }

    public List<Ticket> readTickets(List<UUID> ticketIds, OffsetDateTime now) {
        if (ticketIds.isEmpty()) {
            return List.of();
        }
        var tickets = jdbc.sql("""
                        SELECT t.id, t.code, t.qr_version, t.status::text AS status, t.used_at,
                               o.code AS order_code, t.showtime_id, s.event_id, s.seat_map_id,
                               s.starts_at, s.ends_at, s.sale_opens_at, s.sale_closes_at, s.cancelled_at,
                               e.status::text AS event_status, e.title, e.category,
                               COALESCE(e.poster_desktop_url, e.poster_mobile_url) AS poster_url,
                               v.id AS venue_id, v.name AS venue_name, v.address, v.city,
                               COALESCE(ez.display_name, z.name) AS zone_name,
                               seat.row_label, seat.number::integer AS number, sk.name AS tier_name,
                               (SELECT min(sp2.price)
                                FROM event_zone ez2
                                JOIN LATERAL (
                                    SELECT price FROM sku_price
                                    WHERE sku_id = ez2.sku_id AND effective_from <= :now
                                    ORDER BY effective_from DESC LIMIT 1
                                ) sp2 ON true
                                WHERE ez2.event_id = e.id) AS price_from,
                               (SELECT s2.starts_at FROM showtime s2
                                WHERE s2.event_id = e.id AND e.status = 'PUBLISHED'
                                  AND s2.cancelled_at IS NULL AND s2.starts_at >= :now
                                ORDER BY s2.starts_at, s2.id LIMIT 1) AS first_starts_at,
                               EXISTS (SELECT 1 FROM showtime s2
                                       WHERE s2.event_id = e.id AND e.status = 'PUBLISHED'
                                         AND s2.cancelled_at IS NULL AND s2.starts_at >= :now
                                         AND s2.sale_opens_at <= :now AND :now < s2.sale_closes_at) AS on_sale
                        FROM ticket t
                        JOIN ticket_order o ON o.id = t.order_id
                        JOIN showtime s ON s.id = t.showtime_id
                        JOIN event e ON e.id = s.event_id
                        JOIN seat_map m ON m.id = s.seat_map_id
                        JOIN venue v ON v.id = m.venue_id
                        LEFT JOIN seat ON seat.id = t.seat_id
                        JOIN zone z ON z.id = COALESCE(t.zone_id, seat.zone_id)
                        LEFT JOIN order_seat os ON os.order_id = t.order_id AND os.seat_id = t.seat_id
                        LEFT JOIN order_standing ost ON ost.order_id = t.order_id AND ost.zone_id = t.zone_id
                        JOIN sku_price sp ON sp.id = COALESCE(os.sku_price_id, ost.sku_price_id)
                        JOIN sku sk ON sk.id = sp.sku_id
                        LEFT JOIN event_zone ez ON ez.event_id = e.id AND ez.zone_id = z.id
                        WHERE t.id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", ticketIds.toArray(UUID[]::new)))
                .param("now", now)
                .query((rs, row) -> {
                    UUID ticketId = rs.getObject("id", UUID.class);
                    String statusStr = rs.getString("status");
                    int qrVersion = rs.getInt("qr_version");
                    String qrToken = "ISSUED".equals(statusStr) ? qrCodec.sign(ticketId, qrVersion) : null;

                    String city = rs.getString("city");
                    var venue = new Venue()
                            .id(rs.getObject("venue_id", UUID.class))
                            .name(rs.getString("venue_name"))
                            .address(rs.getString("address"))
                            .cityName(city);
                    var event = new EventSummary()
                            .id(rs.getObject("event_id", UUID.class))
                            .title(rs.getString("title"))
                            .category(EventCategory.fromValue(rs.getString("category")))
                            .venueName(rs.getString("venue_name"))
                            .cityName(city)
                            .priceFrom(rs.getObject("price_from", Long.class))
                            .firstStartsAt(rs.getObject("first_starts_at", OffsetDateTime.class))
                            .onSale(rs.getBoolean("on_sale"));
                    for (CityCode code : CityCode.values()) {
                        if (code.getValue().equals(city)) {
                            venue.city(code);
                            event.city(code);
                            break;
                        }
                    }
                    String poster = rs.getString("poster_url");
                    if (poster != null) {
                        event.posterUrl(URI.create(poster));
                    }
                    OffsetDateTime cancelledAt = rs.getObject("cancelled_at", OffsetDateTime.class);
                    var showtime = new Showtime()
                            .id(rs.getObject("showtime_id", UUID.class))
                            .eventId(rs.getObject("event_id", UUID.class))
                            .seatLayoutId(rs.getObject("seat_map_id", UUID.class))
                            .startsAt(rs.getObject("starts_at", OffsetDateTime.class))
                            .endsAt(rs.getObject("ends_at", OffsetDateTime.class))
                            .saleOpensAt(rs.getObject("sale_opens_at", OffsetDateTime.class))
                            .saleClosesAt(rs.getObject("sale_closes_at", OffsetDateTime.class))
                            .cancelledAt(cancelledAt)
                            .venue(venue)
                            .status(ShowtimeStatus.valueOf(vingo.catalog.ShowtimeStatus.of(
                                    rs.getString("event_status"),
                                    cancelledAt,
                                    rs.getObject("sale_opens_at", OffsetDateTime.class),
                                    rs.getObject("sale_closes_at", OffsetDateTime.class),
                                    rs.getObject("ends_at", OffsetDateTime.class), now).name()));
                    return new Ticket()
                            .id(ticketId)
                            .code(rs.getString("code"))
                            .status(TicketStatus.fromValue(statusStr))
                            .qrToken(qrToken)
                            .orderCode(rs.getString("order_code").strip())
                            .event(event)
                            .showtime(showtime)
                            .venue(venue)
                            .zone(rs.getString("zone_name"))
                            .row(rs.getString("row_label"))
                            .number(rs.getObject("number", Integer.class))
                            .tier(rs.getString("tier_name"))
                            .usedAt(rs.getObject("used_at", OffsetDateTime.class));
                })
                .list();
        Map<UUID, Ticket> byId = new HashMap<>();
        tickets.forEach(ticket -> byId.put(ticket.getId(), ticket));
        return ticketIds.stream().filter(byId::containsKey).map(byId::get).toList();
    }

    public List<CheckinResult> readCheckins(List<UUID> checkinIds) {
        if (checkinIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, CheckinResult> byId = new HashMap<>();
        jdbc.sql("""
                        SELECT c.id, c.result::text AS result, c.scanned_at, t.code, t.used_at,
                               COALESCE(ez.display_name, z.name) AS zone_name,
                               seat.row_label, seat.number::integer AS number
                        FROM ticket_scan c
                        LEFT JOIN ticket t ON t.id = c.ticket_id
                        LEFT JOIN showtime s ON s.id = t.showtime_id
                        LEFT JOIN seat ON seat.id = t.seat_id
                        LEFT JOIN zone z ON z.id = COALESCE(t.zone_id, seat.zone_id)
                        LEFT JOIN event_zone ez ON ez.event_id = s.event_id AND ez.zone_id = z.id
                        WHERE c.id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", checkinIds.toArray(UUID[]::new)))
                .query((rs, row) -> {
                    var result = CheckinResult.ResultEnum.fromValue(rs.getString("result"));
                    var view = new CheckinResult()
                            .result(result)
                            .ticketCode(rs.getString("code"))
                            .zone(rs.getString("zone_name"))
                            .row(rs.getString("row_label"))
                            .number(rs.getObject("number", Integer.class))
                            .scannedAt(rs.getObject("scanned_at", OffsetDateTime.class))
                            .firstUsedAt(result == CheckinResult.ResultEnum.ALREADY_USED
                                    ? rs.getObject("used_at", OffsetDateTime.class) : null);
                    byId.put(rs.getObject("id", UUID.class), view);
                    return view;
                })
                .list();
        return checkinIds.stream().filter(byId::containsKey).map(byId::get).toList();
    }
}
