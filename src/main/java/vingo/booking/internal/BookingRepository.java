package vingo.booking.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import vingo.booking.OrderForPayment;
import vingo.booking.OrderLine;
import vingo.booking.ReviewReason;
import vingo.catalog.ShowtimeStatus;
import vingo.shared.logging.LogKeys;

/** Truy cập đơn và tồn kho bằng JdbcClient trong transaction của BookingService. */
@Repository
public class BookingRepository {

    private static final Logger log = LoggerFactory.getLogger(BookingRepository.class);

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findOrderOwner(UUID orderId) {
        return jdbc.sql("SELECT user_id FROM orders WHERE id = :order")
                .param("order", orderId).query(UUID.class).optional();
    }

    public Optional<OrderForPayment> lockOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, user_id, showtime_id, status, total_amount, expires_at
                        FROM orders WHERE id = :order FOR UPDATE
                        """)
                .param("order", orderId)
                .query((rs, row) -> new OrderForPayment(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("showtime_id", UUID.class),
                        rs.getString("status"), rs.getLong("total_amount"),
                        rs.getObject("expires_at", OffsetDateTime.class))).optional();
    }

    public List<OrderLine> orderLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT item.id, orders.showtime_id, item.zone_id, item.seat_id, item.quantity
                        FROM order_item item JOIN orders ON orders.id = item.order_id
                        WHERE item.order_id = :order ORDER BY item.id
                        """)
                .param("order", orderId)
                .query((rs, row) -> new OrderLine(rs.getObject("id", UUID.class),
                        rs.getObject("showtime_id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getObject("seat_id", UUID.class), rs.getInt("quantity"))).list();
    }

    public boolean lockSeatsForSale(UUID orderId, UUID showtimeId) {
        return jdbc.sql("""
                        WITH locked AS MATERIALIZED (
                            SELECT seat_id, status, order_id FROM showtime_seat
                            WHERE showtime_id = :showtime AND seat_id IN (
                                SELECT seat_id FROM order_item WHERE order_id = :order AND seat_id IS NOT NULL
                            ) ORDER BY seat_id FOR UPDATE
                        )
                        SELECT count(*) FILTER (WHERE status = 'HELD' AND order_id = :order) = (
                            SELECT count(*) FROM order_item WHERE order_id = :order AND seat_id IS NOT NULL
                        ) FROM locked
                        """)
                .param("order", orderId).param("showtime", showtimeId).query(Boolean.class).single();
    }

    public boolean lockStandingForSale(UUID orderId, UUID showtimeId) {
        return jdbc.sql("""
                        WITH items AS (
                            SELECT zone_id, sum(quantity)::integer AS quantity FROM order_item
                            WHERE order_id = :order AND seat_id IS NULL GROUP BY zone_id
                        ), locked AS MATERIALIZED (
                            SELECT zone_id, held FROM showtime_zone_stock
                            WHERE showtime_id = :showtime AND zone_id IN (SELECT zone_id FROM items)
                            ORDER BY zone_id FOR UPDATE
                        )
                        SELECT count(*) FILTER (WHERE locked.held >= items.quantity) = (
                            SELECT count(*) FROM items
                        ) FROM locked JOIN items ON items.zone_id = locked.zone_id
                        """)
                .param("order", orderId).param("showtime", showtimeId).query(Boolean.class).single();
    }

    public void sellSeats(UUID orderId) {
        jdbc.sql("""
                        UPDATE showtime_seat SET status = 'SOLD', held_until = NULL
                        WHERE order_id = :order AND status = 'HELD'
                        """)
                .param("order", orderId).update();
    }

    public boolean sellStanding(UUID orderId, UUID showtimeId) {
        return jdbc.sql("""
                        WITH items AS (
                            SELECT zone_id, sum(quantity)::integer AS quantity FROM order_item
                            WHERE order_id = :order AND seat_id IS NULL GROUP BY zone_id
                        ), sold AS (
                            UPDATE showtime_zone_stock stock
                            SET held = stock.held - items.quantity, sold = stock.sold + items.quantity
                            FROM items
                            WHERE stock.showtime_id = :showtime AND stock.zone_id = items.zone_id
                              AND stock.held >= items.quantity
                              AND NOT EXISTS (
                                  SELECT 1 FROM items requested LEFT JOIN showtime_zone_stock available
                                    ON available.showtime_id = :showtime AND available.zone_id = requested.zone_id
                                  WHERE available.zone_id IS NULL OR available.held < requested.quantity
                              )
                            RETURNING stock.zone_id
                        )
                        SELECT (SELECT count(*) FROM sold) = (SELECT count(*) FROM items)
                        """)
                .param("order", orderId).param("showtime", showtimeId).query(Boolean.class).single();
    }

    public void markPaid(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE orders SET status = 'PAID', paid_at = :now, updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public void markNeedsReview(UUID orderId, ReviewReason reason, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE orders SET status = 'NEEDS_REVIEW', review_reason = :reason, updated_at = :now
                        WHERE id = :order
                        """)
                .param("order", orderId).param("reason", reason.name()).param("now", now).update();
    }

    public List<OrderPosition> listMyOrders(UUID userId, String status, OrderPosition after, int limit) {
        var query = jdbc.sql("""
                        SELECT id, created_at FROM orders WHERE user_id = :user
                        """
                        + (status == null ? "" : " AND status = :status")
                        + (after == null ? "" : " AND (created_at, id) < (:created, :id)")
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("user", userId).param("limit", limit);
        if (status != null) {
            query.param("status", status);
        }
        if (after != null) {
            query.param("created", after.createdAt()).param("id", after.id());
        }
        return query.query((rs, row) -> new OrderPosition(rs.getObject("id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public void cancelOrder(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE orders SET status = 'CANCELLED', updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    // shortcut: đọc thẳng bảng catalog (D4 chờ Luân), đổi sang CatalogService khi có.
    public Optional<Sale> findSale(UUID showtimeId, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT s.status, s.sale_opens_at, s.sale_closes_at, s.ends_at,
                               e.status AS event_status, e.max_tickets_per_order
                        FROM showtime s JOIN event e ON e.id = s.event_id WHERE s.id = :showtime
                        """)
                .param("showtime", showtimeId)
                .query((rs, row) -> new Sale(rs.getString("status"),
                        "PUBLISHED".equals(rs.getString("event_status"))
                                && ShowtimeStatus.of(rs.getString("status"),
                                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                                        rs.getObject("ends_at", OffsetDateTime.class), now) == ShowtimeStatus.ON_SALE,
                        rs.getInt("max_tickets_per_order"))).optional();
    }

    public Optional<PendingOrder> lockPendingOrder(UUID userId, UUID showtimeId, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT id, user_id, showtime_id, expires_at <= :now AS overdue FROM orders
                        WHERE user_id = :user AND showtime_id = :showtime AND status = 'PENDING' FOR UPDATE
                        """)
                .param("user", userId).param("showtime", showtimeId).param("now", now)
                .query((rs, row) -> new PendingOrder(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("showtime_id", UUID.class),
                        rs.getBoolean("overdue"))).optional();
    }

    public List<PendingOrder> lockOverduePendingOrders(OffsetDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id, user_id, showtime_id FROM orders
                        WHERE status = 'PENDING' AND expires_at <= :now
                        ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
                        """)
                .param("now", now).param("limit", limit)
                .query((rs, row) -> new PendingOrder(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("showtime_id", UUID.class),
                        true)).list();
    }

    // Khoá hợp chỗ cũ và mới: ghế trước, khu đứng sau, tránh khoá chéo khi thay đơn quá hạn.
    public void lockReplacementInventory(PendingOrder old, Collection<UUID> seatIds, UUID zoneId) {
        jdbc.sql("""
                        SELECT seat_id FROM showtime_seat
                        WHERE showtime_id = :showtime AND (
                            (order_id = :order AND status = 'HELD') OR seat_id = ANY(:ids)
                            OR seat_id IN (
                                SELECT seat_b_id FROM seat_pair WHERE seat_a_id = ANY(:ids)
                                UNION SELECT seat_a_id FROM seat_pair WHERE seat_b_id = ANY(:ids)
                            )
                        ) ORDER BY seat_id FOR UPDATE
                        """)
                .param("showtime", old.showtimeId()).param("order", old.id())
                .param("ids", new SqlArrayValue("uuid", seatIds.toArray())).query(UUID.class).list();
        jdbc.sql("""
                        SELECT zone_id FROM showtime_zone_stock
                        WHERE showtime_id = :showtime AND (zone_id = :zone OR zone_id IN (
                            SELECT zone_id FROM order_item WHERE order_id = :order AND seat_id IS NULL
                        )) ORDER BY zone_id FOR UPDATE
                        """)
                .param("showtime", old.showtimeId()).param("order", old.id())
                .param("zone", zoneId, Types.OTHER).query(UUID.class).list();
    }

    public List<UUID> pairedSeats(Collection<UUID> seatIds) {
        return jdbc.sql("""
                        SELECT seat_b_id FROM seat_pair WHERE seat_a_id = ANY(:ids)
                        UNION SELECT seat_a_id FROM seat_pair WHERE seat_b_id = ANY(:ids)
                        """)
                .param("ids", new SqlArrayValue("uuid", seatIds.toArray())).query(UUID.class).list();
    }

    public List<HoldItem> seatItems(UUID showtimeId, Collection<UUID> seatIds) {
        return jdbc.sql("""
                        SELECT ss.seat_id, z.id AS zone_id, pt.id AS price_tier_id, pt.price
                        FROM showtime_seat ss JOIN showtime st ON st.id = ss.showtime_id
                        JOIN seat s ON s.id = ss.seat_id
                        JOIN zone z ON z.id = s.zone_id AND z.seat_layout_id = st.seat_layout_id
                        LEFT JOIN zone_price zp ON zp.zone_id = z.id AND zp.event_id = st.event_id
                        LEFT JOIN price_tier pt ON pt.id = zp.price_tier_id AND pt.event_id = st.event_id
                        WHERE ss.showtime_id = :showtime AND ss.seat_id = ANY(:ids) AND z.kind = 'SEATED'
                        ORDER BY ss.seat_id
                        """)
                .param("showtime", showtimeId).param("ids", new SqlArrayValue("uuid", seatIds.toArray()))
                .query((rs, row) -> new HoldItem(rs.getObject("seat_id", UUID.class),
                        rs.getObject("zone_id", UUID.class), rs.getObject("price_tier_id", UUID.class),
                        1, rs.getLong("price"))).list();
    }

    public Optional<HoldItem> standingItem(UUID showtimeId, UUID zoneId, int quantity) {
        return jdbc.sql("""
                        SELECT z.id AS zone_id, pt.id AS price_tier_id, pt.price
                        FROM showtime_zone_stock stock JOIN showtime st ON st.id = stock.showtime_id
                        JOIN zone z ON z.id = stock.zone_id AND z.seat_layout_id = st.seat_layout_id
                        LEFT JOIN zone_price zp ON zp.zone_id = z.id AND zp.event_id = st.event_id
                        LEFT JOIN price_tier pt ON pt.id = zp.price_tier_id AND pt.event_id = st.event_id
                        WHERE stock.showtime_id = :showtime AND stock.zone_id = :zone AND z.kind = 'STANDING'
                        """)
                .param("showtime", showtimeId).param("zone", zoneId)
                .query((rs, row) -> new HoldItem(null, rs.getObject("zone_id", UUID.class),
                        rs.getObject("price_tier_id", UUID.class), quantity, rs.getLong("price"))).optional();
    }

    public Optional<UUID> insertOrder(String code, UUID userId, UUID showtimeId, long total,
                                      OffsetDateTime expiresAt, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO orders (code, user_id, showtime_id, status, total_amount, expires_at,
                                            created_at, updated_at)
                        VALUES (:code, :user, :showtime, 'PENDING', :total, :expires, :now, :now)
                        ON CONFLICT (code) DO NOTHING RETURNING id
                        """)
                .param("code", code).param("user", userId).param("showtime", showtimeId).param("total", total)
                .param("expires", expiresAt).param("now", now).query(UUID.class).optional();
    }

    public List<UUID> holdSeats(UUID showtimeId, List<UUID> seatIds, UUID orderId,
                                OffsetDateTime expiresAt, OffsetDateTime now) {
        return jdbc.sql("""
                        UPDATE showtime_seat ss SET status = 'HELD', order_id = :order, held_until = :expires
                        FROM (
                            SELECT seat_id FROM showtime_seat
                            WHERE showtime_id = :showtime AND seat_id = ANY(:ids)
                              AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < :now))
                            ORDER BY seat_id FOR UPDATE
                        ) f
                        WHERE ss.showtime_id = :showtime AND ss.seat_id = f.seat_id RETURNING ss.seat_id
                        """)
                .param("showtime", showtimeId).param("ids", new SqlArrayValue("uuid", seatIds.toArray()))
                .param("order", orderId).param("expires", expiresAt).param("now", now)
                .query(UUID.class).list();
    }

    public boolean holdStanding(UUID showtimeId, UUID zoneId, int quantity) {
        return jdbc.sql("""
                        UPDATE showtime_zone_stock SET held = held + :n
                        WHERE showtime_id = :showtime AND zone_id = :zone AND capacity - sold - held >= :n
                        """)
                .param("showtime", showtimeId).param("zone", zoneId).param("n", quantity).update() == 1;
    }

    public int seatsLeft(UUID showtimeId, UUID zoneId) {
        return jdbc.sql("""
                        SELECT GREATEST(capacity - sold - held, 0) FROM showtime_zone_stock
                        WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("showtime", showtimeId).param("zone", zoneId).query(Integer.class).single();
    }

    public void insertItem(UUID orderId, HoldItem item) {
        jdbc.sql("""
                        INSERT INTO order_item (order_id, zone_id, seat_id, price_tier_id, quantity, unit_price)
                        VALUES (:order, :zone, :seat, :tier, :quantity, :price)
                        """)
                .param("order", orderId).param("zone", item.zoneId()).param("seat", item.seatId(), Types.OTHER)
                .param("tier", item.priceTierId()).param("quantity", item.quantity())
                .param("price", item.unitPrice()).update();
    }

    public void insertHistory(UUID orderId, String from, String to, UUID actorId, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO order_status_history (order_id, from_status, to_status, actor_id, at)
                        VALUES (:order, :from, :to, :actor, :now)
                        """)
                .param("order", orderId).param("from", from, Types.VARCHAR).param("to", to)
                .param("actor", actorId, Types.OTHER).param("now", now).update();
    }

    public void releaseSeats(UUID orderId) {
        jdbc.sql("""
                        UPDATE showtime_seat ss SET status = 'AVAILABLE', order_id = NULL, held_until = NULL
                        FROM (
                            SELECT showtime_id, seat_id FROM showtime_seat
                            WHERE order_id = :order AND status = 'HELD' ORDER BY seat_id FOR UPDATE
                        ) f
                        WHERE ss.showtime_id = f.showtime_id AND ss.seat_id = f.seat_id
                          AND ss.order_id = :order AND ss.status = 'HELD'
                        """)
                .param("order", orderId).update();
    }

    public void releaseStanding(UUID orderId, UUID showtimeId) {
        boolean skipped = jdbc.sql("""
                        WITH locked AS MATERIALIZED (
                            SELECT stock.showtime_id, stock.zone_id, items.quantity
                            FROM showtime_zone_stock stock JOIN (
                                SELECT zone_id, sum(quantity)::integer AS quantity FROM order_item
                                WHERE order_id = :order AND seat_id IS NULL GROUP BY zone_id
                            ) items ON items.zone_id = stock.zone_id
                            WHERE stock.showtime_id = :showtime
                            ORDER BY stock.zone_id FOR UPDATE OF stock
                        ), released AS (
                            UPDATE showtime_zone_stock stock SET held = stock.held - items.quantity
                            FROM locked items
                            WHERE stock.showtime_id = items.showtime_id AND stock.zone_id = items.zone_id
                              AND stock.held >= items.quantity
                            RETURNING stock.zone_id
                        )
                        SELECT (SELECT count(*) FROM released) < (SELECT count(*) FROM locked)
                        """)
                .param("order", orderId).param("showtime", showtimeId).query(Boolean.class).single();
        // Chặn held âm nhưng vẫn báo lệch tồn kho để không che lỗi ở nơi khác.
        if (skipped) {
            log.atError().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue("showtime_id", showtimeId)
                    .log("held khu đứng lệch, không trả held");
        }
    }

    public void expireOrder(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE orders SET status = 'EXPIRED', updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public record Sale(String status, boolean onSale, int maxTicketsPerOrder) {}

    public record OrderPosition(UUID id, OffsetDateTime createdAt) {}

    public record PendingOrder(UUID id, UUID userId, UUID showtimeId, boolean overdue) {}

    public record HoldItem(UUID seatId, UUID zoneId, UUID priceTierId, int quantity, long unitPrice) {}
}
