package vingo.booking.internal;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
        Order order = jdbc.sql("""
                        SELECT o.*, s.event_id, s.hall_id, s.seat_layout_id, s.starts_at, s.ends_at,
                               s.sale_opens_at, s.sale_closes_at,
                               CASE WHEN s.status IN ('DRAFT', 'CANCELLED') THEN s.status
                                    WHEN :now >= s.ends_at THEN 'FINISHED'
                                    WHEN :now >= s.sale_closes_at THEN 'SALES_CLOSED'
                                    WHEN :now >= s.sale_opens_at AND e.status = 'PUBLISHED' THEN 'ON_SALE'
                                    ELSE 'SCHEDULED' END AS showtime_status,
                               e.title, e.category, e.poster_url, v.name AS venue_name, v.city
                        FROM orders o JOIN showtime s ON s.id = o.showtime_id
                        JOIN event e ON e.id = s.event_id
                        JOIN hall h ON h.id = s.hall_id JOIN venue v ON v.id = h.venue_id
                        WHERE o.id = :order
                        """)
                .param("order", orderId).param("now", now)
                .query((rs, row) -> {
                    // shortcut: bỏ các trường EventSummary tính toán, bổ sung khi CatalogService cung cấp.
                    String city = rs.getString("city");
                    var event = new EventSummary().id(rs.getObject("event_id", UUID.class))
                            .title(rs.getString("title")).category(EventCategory.fromValue(rs.getString("category")))
                            .venueName(rs.getString("venue_name")).cityName(city);
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
                            .status(ShowtimeStatus.fromValue(rs.getString("showtime_status")));
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
                }).optional().orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));

        var items = jdbc.sql("""
                        SELECT i.id, i.seat_id, i.unit_price, i.quantity, z.name AS zone_name,
                               s.row_label, s.number, pt.name AS tier_name
                        FROM order_item i JOIN zone z ON z.id = i.zone_id
                        JOIN price_tier pt ON pt.id = i.price_tier_id LEFT JOIN seat s ON s.id = i.seat_id
                        WHERE i.order_id = :order ORDER BY z.name, s.row_label, s.number, i.id
                        """)
                .param("order", orderId)
                .query((rs, row) -> new OrderItem().id(rs.getObject("id", UUID.class))
                        .zone(rs.getString("zone_name")).row(rs.getString("row_label"))
                        .number(rs.getObject("number", Integer.class)).seatId(rs.getObject("seat_id", UUID.class))
                        .tier(rs.getString("tier_name")).unitPrice(rs.getLong("unit_price"))
                        .quantity(rs.getInt("quantity")))
                .list();
        return order.items(items);
    }
}
