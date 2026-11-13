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

/** Đọc cả trang vé hoặc lượt quét bằng một truy vấn chi tiết, giữ thứ tự keyset của caller. */
@Repository
public class TicketingReader {

    private final JdbcClient jdbc;

    /** Dùng JdbcClient để đọc trong transaction của use case. */
    public TicketingReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // shortcut: đọc thẳng bảng catalog/booking, đổi sang service khi có
    /** Đọc chi tiết vé cùng catalog, chỉ đưa QR vào model khi vé còn ISSUED. */
    public List<Ticket> readTickets(List<UUID> ticketIds, OffsetDateTime now) {
        if (ticketIds.isEmpty()) {
            return List.of();
        }
        var tickets = jdbc.sql("""
                        SELECT t.id, t.code, t.status, t.used_at,
                               CASE WHEN t.status = 'ISSUED' THEN t.qr_token END AS visible_qr_token,
                               o.code AS order_code, t.showtime_id, s.event_id, s.hall_id, s.seat_layout_id,
                               s.starts_at, s.ends_at, s.sale_opens_at, s.sale_closes_at,
                               s.status AS stored_showtime_status, e.title, e.category, e.poster_url,
                               v.id AS venue_id, v.name AS venue_name, v.type AS venue_type, v.address, v.city,
                               z.name AS zone_name, seat.row_label, seat.number, pt.name AS tier_name,
                               (SELECT min(tier.price) FROM zone_price zp
                                JOIN price_tier tier ON tier.id = zp.price_tier_id
                                WHERE zp.event_id = e.id) AS price_from,
                               (SELECT s2.starts_at FROM showtime s2
                                WHERE s2.event_id = e.id AND s2.status = 'SCHEDULED' AND s2.starts_at >= :now
                                ORDER BY s2.starts_at, s2.id LIMIT 1) AS first_starts_at,
                               EXISTS (SELECT 1 FROM showtime s2
                                       WHERE s2.event_id = e.id AND s2.status = 'SCHEDULED'
                                         AND s2.starts_at >= :now AND s2.sale_opens_at <= :now
                                         AND :now < s2.sale_closes_at) AS on_sale
                        FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        JOIN orders o ON o.id = oi.order_id JOIN showtime s ON s.id = t.showtime_id
                        JOIN event e ON e.id = s.event_id JOIN hall h ON h.id = s.hall_id
                        JOIN venue v ON v.id = h.venue_id JOIN zone z ON z.id = t.zone_id
                        JOIN price_tier pt ON pt.id = oi.price_tier_id LEFT JOIN seat ON seat.id = t.seat_id
                        WHERE t.id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", ticketIds.toArray(UUID[]::new))).param("now", now)
                .query((rs, row) -> {
                    String city = rs.getString("city");
                    var venue = new Venue().id(rs.getObject("venue_id", UUID.class)).name(rs.getString("venue_name"))
                            .type(Venue.TypeEnum.fromValue(rs.getString("venue_type")))
                            .address(rs.getString("address")).cityName(city);
                    var event = new EventSummary().id(rs.getObject("event_id", UUID.class))
                            .title(rs.getString("title")).category(EventCategory.fromValue(rs.getString("category")))
                            .venueName(rs.getString("venue_name")).cityName(city)
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
                    var showtime = new Showtime().id(rs.getObject("showtime_id", UUID.class))
                            .eventId(rs.getObject("event_id", UUID.class)).hallId(rs.getObject("hall_id", UUID.class))
                            .seatLayoutId(rs.getObject("seat_layout_id", UUID.class))
                            .startsAt(rs.getObject("starts_at", OffsetDateTime.class))
                            .endsAt(rs.getObject("ends_at", OffsetDateTime.class))
                            .saleOpensAt(rs.getObject("sale_opens_at", OffsetDateTime.class))
                            .saleClosesAt(rs.getObject("sale_closes_at", OffsetDateTime.class))
                            .venue(venue).status(ShowtimeStatus.valueOf(vingo.catalog.ShowtimeStatus.of(
                                    rs.getString("stored_showtime_status"),
                                    rs.getObject("sale_opens_at", OffsetDateTime.class),
                                    rs.getObject("sale_closes_at", OffsetDateTime.class),
                                    rs.getObject("ends_at", OffsetDateTime.class), now).name()));
                    return new Ticket().id(rs.getObject("id", UUID.class)).code(rs.getString("code"))
                            .status(TicketStatus.fromValue(rs.getString("status")))
                            .qrToken(rs.getString("visible_qr_token")).orderCode(rs.getString("order_code"))
                            .event(event).showtime(showtime).venue(venue).zone(rs.getString("zone_name"))
                            .row(rs.getString("row_label")).number(rs.getObject("number", Integer.class))
                            .tier(rs.getString("tier_name")).usedAt(rs.getObject("used_at", OffsetDateTime.class));
                }).list();
        Map<UUID, Ticket> byId = new HashMap<>();
        tickets.forEach(ticket -> byId.put(ticket.getId(), ticket));
        return ticketIds.stream().filter(byId::containsKey).map(byId::get).toList();
    }

    /** Đọc kết quả từng lượt quét theo thứ tự id trang, giữ nguyên scanned_at và kết quả đã lưu. */
    public List<CheckinResult> readCheckins(List<UUID> checkinIds) {
        if (checkinIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, CheckinResult> byId = new HashMap<>();
        jdbc.sql("""
                        SELECT c.id, c.result, c.scanned_at, t.code, t.used_at,
                               z.name AS zone_name, s.row_label, s.number
                        FROM checkin c JOIN ticket t ON t.id = c.ticket_id
                        JOIN zone z ON z.id = t.zone_id LEFT JOIN seat s ON s.id = t.seat_id
                        WHERE c.id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", checkinIds.toArray(UUID[]::new)))
                .query((rs, row) -> {
                    var result = CheckinResult.ResultEnum.fromValue(rs.getString("result"));
                    var view = new CheckinResult().result(result).ticketCode(rs.getString("code"))
                            .zone(rs.getString("zone_name")).row(rs.getString("row_label"))
                            .number(rs.getObject("number", Integer.class))
                            .scannedAt(rs.getObject("scanned_at", OffsetDateTime.class))
                            .firstUsedAt(result == CheckinResult.ResultEnum.ALREADY_USED
                                    ? rs.getObject("used_at", OffsetDateTime.class) : null);
                    byId.put(rs.getObject("id", UUID.class), view);
                    return view;
                }).list();
        return checkinIds.stream().filter(byId::containsKey).map(byId::get).toList();
    }
}
