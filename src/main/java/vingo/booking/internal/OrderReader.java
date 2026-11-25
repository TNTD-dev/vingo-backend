package vingo.booking.internal;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.AdminGetOrder200Response;
import vingo.api.model.AdminGetOrder200ResponseAllOfHistoryInner;
import vingo.api.model.Buyer;
import vingo.api.model.EventCategory;
import vingo.api.model.EventSummary;
import vingo.api.model.Order;
import vingo.api.model.OrderItem;
import vingo.api.model.OrderStatus;
import vingo.api.model.Showtime;
import vingo.api.model.ShowtimeStatus;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Đọc chi tiết đơn theo model API để dùng chung cho giữ chỗ và xem đơn. */
@Repository
public class OrderReader {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OrderReader(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Đơn kèm history cho admin. */
    public AdminGetOrder200Response readAdminOrder(UUID orderId, OffsetDateTime now) {
        return json.convertValue(readOrder(orderId, now), AdminGetOrder200Response.class)
                .history(readHistory(orderId));
    }

    public Order readOrder(UUID orderId, OffsetDateTime now) {
        return readOrders(List.of(orderId), now).stream().findFirst()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
    }

    /** Đọc history theo at/id tăng dần, trả email actor hoặc SYSTEM bằng một query. */
    public List<AdminGetOrder200ResponseAllOfHistoryInner> readHistory(UUID orderId) {
        return jdbc.sql("""
                        SELECT h.to_status::text AS to_status, h.at, COALESCE(u.email, 'SYSTEM') AS actor
                        FROM order_status_history h
                        LEFT JOIN user_account u ON u.id = h.actor_id
                        WHERE h.order_id = :order
                        ORDER BY h.at, h.id
                        """)
                .param("order", orderId)
                .query((rs, row) -> new AdminGetOrder200ResponseAllOfHistoryInner()
                        .status(OrderStatus.fromValue(rs.getString("to_status")))
                        .at(rs.getObject("at", OffsetDateTime.class))
                        .actor(rs.getString("actor")))
                .list();
    }

    public List<Order> readOrders(List<UUID> orderIds, OffsetDateTime now) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        UUID[] ids = orderIds.toArray(UUID[]::new);
        var orders = jdbc.sql("""
                        SELECT o.id, o.showtime_id, o.code, o.status::text AS status, o.total_amount,
                               o.expires_at, o.paid_at, o.buyer_name, o.buyer_email, o.buyer_phone, o.created_at,
                               s.event_id, s.seat_map_id, s.starts_at, s.ends_at,
                               s.sale_opens_at, s.sale_closes_at, s.cancelled_at,
                               e.status::text AS event_status,
                               e.title, e.category, COALESCE(e.poster_desktop_url, e.poster_mobile_url) AS poster_url,
                               v.name AS venue_name, v.city,
                               (SELECT min(sp.price)
                                FROM event_zone ez
                                JOIN zone z ON z.id = ez.zone_id AND z.seat_map_id = ez.seat_map_id
                                JOIN LATERAL (
                                    SELECT price FROM ticket_type_price
                                    WHERE ticket_type_id = ez.ticket_type_id AND effective_from <= :now
                                    ORDER BY effective_from DESC LIMIT 1
                                ) sp ON true
                                WHERE ez.event_id = e.id
                                  AND EXISTS (
                                      SELECT 1 FROM sku sk
                                      WHERE sk.event_id = e.id
                                        AND sk.ticket_type_id = ez.ticket_type_id
                                        AND (
                                            (z.kind = 'SEATED' AND EXISTS (
                                                SELECT 1 FROM seat se
                                                WHERE se.id = sk.seat_id AND se.zone_id = z.id
                                            ))
                                            OR (z.kind = 'STANDING' AND sk.zone_id = z.id)
                                        )
                                  )) AS price_from,
                               (SELECT s2.starts_at FROM showtime s2
                                WHERE s2.event_id = e.id AND e.status = 'PUBLISHED'
                                  AND s2.cancelled_at IS NULL AND s2.starts_at >= :now
                                ORDER BY s2.starts_at, s2.id LIMIT 1) AS first_starts_at,
                               EXISTS (SELECT 1 FROM showtime s2
                                       WHERE s2.event_id = e.id AND e.status = 'PUBLISHED'
                                         AND s2.cancelled_at IS NULL AND s2.starts_at >= :now
                                         AND s2.sale_opens_at <= :now AND :now < s2.sale_closes_at) AS on_sale,
                               (SELECT h.reason FROM order_status_history h
                                WHERE h.order_id = o.id AND h.to_status = 'NEEDS_REVIEW'
                                ORDER BY h.at DESC, h.id DESC LIMIT 1) AS review_reason
                        FROM ticket_order o
                        JOIN showtime s ON s.id = o.showtime_id
                        JOIN event e ON e.id = s.event_id
                        JOIN seat_map m ON m.id = s.seat_map_id
                        JOIN venue v ON v.id = m.venue_id
                        WHERE o.id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", ids))
                .param("now", now)
                .query((rs, row) -> {
                    String city = rs.getString("city");
                    var event = new EventSummary()
                            .id(rs.getObject("event_id", UUID.class))
                            .title(rs.getString("title"))
                            .category(EventCategory.fromValue(rs.getString("category")))
                            .venueName(rs.getString("venue_name"))
                            .city(city).cityName(city)
                            .priceFrom(rs.getObject("price_from", Long.class))
                            .firstStartsAt(rs.getObject("first_starts_at", OffsetDateTime.class))
                            .onSale(rs.getBoolean("on_sale"));
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
                            .status(ShowtimeStatus.valueOf(vingo.catalog.ShowtimeStatus.of(
                                    rs.getString("event_status"),
                                    cancelledAt,
                                    rs.getObject("sale_opens_at", OffsetDateTime.class),
                                    rs.getObject("sale_closes_at", OffsetDateTime.class),
                                    rs.getObject("ends_at", OffsetDateTime.class), now).name()));
                    var result = new Order()
                            .id(rs.getObject("id", UUID.class))
                            .code(rs.getString("code").strip())
                            .status(OrderStatus.fromValue(rs.getString("status")))
                            .event(event)
                            .showtime(showtime)
                            .currency(Order.CurrencyEnum.VND)
                            .totalAmount(rs.getLong("total_amount"))
                            .expiresAt(rs.getObject("expires_at", OffsetDateTime.class))
                            .paidAt(rs.getObject("paid_at", OffsetDateTime.class))
                            .createdAt(rs.getObject("created_at", OffsetDateTime.class));
                    String reason = rs.getString("review_reason");
                    if (reason != null) {
                        try {
                            result.reviewReason(Order.ReviewReasonEnum.fromValue(reason));
                        } catch (IllegalArgumentException ignored) {
                            // Bỏ qua nếu reason không nằm trong enum cố định.
                        }
                    }
                    String name = rs.getString("buyer_name");
                    String email = rs.getString("buyer_email");
                    String phone = rs.getString("buyer_phone");
                    if (name != null || email != null || phone != null) {
                        result.buyer(new Buyer().fullName(name).email(email).phone(phone));
                    }
                    return result;
                })
                .list();

        Map<UUID, List<OrderItem>> itemsByOrder = new HashMap<>();
        jdbc.sql("""
                        SELECT os.order_id, os.seat_id AS item_id, os.seat_id, os.sku_id,
                               COALESCE(os.ticket_type_id, ttp.ticket_type_id) AS ticket_type_id,
                               os.unit_price, 1 AS quantity,
                               COALESCE(ez.display_name, z.name) AS zone_name,
                               s.row_label, s.number::integer AS number,
                               tt.name AS tier_name
                        FROM order_seat os
                        JOIN showtime st ON st.id = os.showtime_id
                        JOIN seat s ON s.id = os.seat_id
                        JOIN zone z ON z.id = s.zone_id
                        JOIN ticket_type_price ttp ON ttp.id = os.ticket_type_price_id
                        JOIN ticket_type tt ON tt.id = COALESCE(os.ticket_type_id, ttp.ticket_type_id)
                        LEFT JOIN event_zone ez ON ez.event_id = st.event_id AND ez.zone_id = z.id
                        WHERE os.order_id = ANY(:ids)
                        UNION ALL
                        SELECT ost.order_id, ost.zone_id AS item_id, NULL::uuid AS seat_id, ost.sku_id,
                               COALESCE(ost.ticket_type_id, ttp.ticket_type_id) AS ticket_type_id,
                               ost.unit_price, ost.quantity::integer,
                               COALESCE(ez.display_name, z.name) AS zone_name,
                               NULL::text AS row_label, NULL::integer AS number,
                               tt.name AS tier_name
                        FROM order_standing ost
                        JOIN showtime st ON st.id = ost.showtime_id
                        JOIN zone z ON z.id = ost.zone_id
                        JOIN ticket_type_price ttp ON ttp.id = ost.ticket_type_price_id
                        JOIN ticket_type tt ON tt.id = COALESCE(ost.ticket_type_id, ttp.ticket_type_id)
                        LEFT JOIN event_zone ez ON ez.event_id = st.event_id AND ez.zone_id = z.id
                        WHERE ost.order_id = ANY(:ids)
                        ORDER BY order_id, zone_name, row_label, number, item_id
                        """)
                .param("ids", new SqlArrayValue("uuid", ids))
                .query((rs, row) -> {
                    String tierName = rs.getString("tier_name");
                    var item = new OrderItem()
                            .id(rs.getObject("item_id", UUID.class))
                            .skuId(rs.getObject("sku_id", UUID.class))
                            .ticketTypeId(rs.getObject("ticket_type_id", UUID.class))
                            .zone(rs.getString("zone_name"))
                            .row(rs.getString("row_label"))
                            .number(rs.getObject("number", Integer.class))
                            .seatId(rs.getObject("seat_id", UUID.class))
                            .tier(tierName)
                            .ticketType(tierName)
                            .unitPrice(rs.getLong("unit_price"))
                            .quantity(rs.getInt("quantity"));
                    itemsByOrder.computeIfAbsent(rs.getObject("order_id", UUID.class), id -> new ArrayList<>())
                            .add(item);
                    return item;
                })
                .list();
        Map<UUID, Order> ordersById = new HashMap<>();
        orders.forEach(order -> ordersById.put(order.getId(),
                order.items(itemsByOrder.getOrDefault(order.getId(), List.of()))));
        return orderIds.stream().filter(ordersById::containsKey).map(ordersById::get).toList();
    }
}
