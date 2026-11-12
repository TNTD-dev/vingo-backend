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

import vingo.api.model.AdminGetOrder200ResponseAllOfHistoryInner;
import vingo.api.model.BuyerProfile;
import vingo.api.model.CityCode;
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

    public OrderReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // shortcut: đọc thẳng bảng catalog (D4 chờ Luân), đổi sang CatalogService khi có.
    public Order readOrder(UUID orderId, OffsetDateTime now) {
        return readOrders(List.of(orderId), now).stream().findFirst()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
    }

    /** Đọc history theo at/id tăng dần, trả email actor hoặc SYSTEM bằng một query. */
    public List<AdminGetOrder200ResponseAllOfHistoryInner> readHistory(UUID orderId) {
        // shortcut: đọc thẳng bảng users của auth, đổi sang AuthService khi có
        return jdbc.sql("""
                        SELECT h.to_status, h.at, COALESCE(u.email, 'SYSTEM') AS actor
                        FROM order_status_history h LEFT JOIN users u ON u.id = h.actor_id
                        WHERE h.order_id = :order ORDER BY h.at, h.id
                        """)
                .param("order", orderId).query((rs, row) -> new AdminGetOrder200ResponseAllOfHistoryInner()
                        .status(OrderStatus.fromValue(rs.getString("to_status")))
                        .at(rs.getObject("at", OffsetDateTime.class)).actor(rs.getString("actor"))).list();
    }

    public List<Order> readOrders(List<UUID> orderIds, OffsetDateTime now) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        UUID[] ids = orderIds.toArray(UUID[]::new);
        var orders = jdbc.sql("""
                        SELECT o.*, s.event_id, s.hall_id, s.seat_layout_id, s.starts_at, s.ends_at,
                               s.sale_opens_at, s.sale_closes_at,
                               s.status AS stored_showtime_status,
                               e.title, e.category, e.poster_url, v.name AS venue_name, v.city,
                               (SELECT min(pt.price) FROM zone_price zp
                                JOIN price_tier pt ON pt.id = zp.price_tier_id
                                WHERE zp.event_id = e.id) AS price_from,
                               (SELECT s2.starts_at FROM showtime s2
                                WHERE s2.event_id = e.id AND s2.status = 'SCHEDULED' AND s2.starts_at >= :now
                                ORDER BY s2.starts_at, s2.id LIMIT 1) AS first_starts_at,
                               EXISTS (SELECT 1 FROM showtime s2
                                       WHERE s2.event_id = e.id AND s2.status = 'SCHEDULED'
                                         AND s2.starts_at >= :now AND s2.sale_opens_at <= :now
                                         AND :now < s2.sale_closes_at) AS on_sale
                        FROM orders o JOIN showtime s ON s.id = o.showtime_id
                        JOIN event e ON e.id = s.event_id
                        JOIN hall h ON h.id = s.hall_id JOIN venue v ON v.id = h.venue_id
                        WHERE o.id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", ids)).param("now", now)
                .query((rs, row) -> {
                    String city = rs.getString("city");
                    var event = new EventSummary().id(rs.getObject("event_id", UUID.class))
                            .title(rs.getString("title")).category(EventCategory.fromValue(rs.getString("category")))
                            .venueName(rs.getString("venue_name")).cityName(city)
                            .priceFrom(rs.getObject("price_from", Long.class))
                            .firstStartsAt(rs.getObject("first_starts_at", OffsetDateTime.class))
                            .onSale(rs.getBoolean("on_sale"));
                    // shortcut: venue.city là text tự do, catalog chốt mã thành phố thì đổi.
                    for (CityCode code : CityCode.values()) {
                        if (code.getValue().equals(city)) {
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
                            .status(ShowtimeStatus.valueOf(vingo.catalog.ShowtimeStatus.of(
                                    rs.getString("stored_showtime_status"),
                                    rs.getObject("sale_opens_at", OffsetDateTime.class),
                                    rs.getObject("sale_closes_at", OffsetDateTime.class),
                                    rs.getObject("ends_at", OffsetDateTime.class), now).name()));
                    var result = new Order().id(rs.getObject("id", UUID.class)).code(rs.getString("code"))
                            .status(OrderStatus.fromValue(rs.getString("status"))).event(event).showtime(showtime)
                            .currency(Order.CurrencyEnum.VND).totalAmount(rs.getLong("total_amount"))
                            .expiresAt(rs.getObject("expires_at", OffsetDateTime.class))
                            .paidAt(rs.getObject("paid_at", OffsetDateTime.class))
                            .createdAt(rs.getObject("created_at", OffsetDateTime.class));
                    String reason = rs.getString("review_reason");
                    if (reason != null) {
                        result.reviewReason(Order.ReviewReasonEnum.fromValue(reason));
                    }
                    String name = rs.getString("buyer_name");
                    String email = rs.getString("buyer_email");
                    String phone = rs.getString("buyer_phone");
                    if (name != null || email != null || phone != null) {
                        result.buyer(new BuyerProfile().fullName(name).email(email).phone(phone));
                    }
                    return result;
                }).list();

        Map<UUID, List<OrderItem>> itemsByOrder = new HashMap<>();
        jdbc.sql("""
                        SELECT i.order_id, i.id, i.seat_id, i.unit_price, i.quantity, z.name AS zone_name,
                               s.row_label, s.number, pt.name AS tier_name
                        FROM order_item i JOIN zone z ON z.id = i.zone_id
                        JOIN price_tier pt ON pt.id = i.price_tier_id LEFT JOIN seat s ON s.id = i.seat_id
                        WHERE i.order_id = ANY(:ids) ORDER BY i.order_id, z.name, s.row_label, s.number, i.id
                        """)
                .param("ids", new SqlArrayValue("uuid", ids))
                .query((rs, row) -> {
                    var item = new OrderItem().id(rs.getObject("id", UUID.class))
                            .zone(rs.getString("zone_name")).row(rs.getString("row_label"))
                            .number(rs.getObject("number", Integer.class)).seatId(rs.getObject("seat_id", UUID.class))
                            .tier(rs.getString("tier_name")).unitPrice(rs.getLong("unit_price"))
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
