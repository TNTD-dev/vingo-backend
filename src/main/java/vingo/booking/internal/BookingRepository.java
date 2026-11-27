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
        return jdbc.sql("SELECT user_id FROM ticket_order WHERE id = :order")
                .param("order", orderId).query(UUID.class).optional();
    }

    public Optional<OrderForPayment> lockOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, user_id, showtime_id, status::text AS status, total_amount, expires_at
                        FROM ticket_order WHERE id = :order FOR UPDATE
                        """)
                .param("order", orderId)
                .query((rs, row) -> new OrderForPayment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("showtime_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("total_amount"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    public boolean hasBuyer(UUID orderId) {
        return jdbc.sql("SELECT buyer_email IS NOT NULL FROM ticket_order WHERE id = :order")
                .param("order", orderId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public void saveBuyer(UUID orderId, String fullName, String email, String phone, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE ticket_order
                        SET buyer_name = :name, buyer_email = :email, buyer_phone = :phone, updated_at = :now
                        WHERE id = :order AND status = 'PENDING'
                        """)
                .param("order", orderId)
                .param("name", fullName)
                .param("email", email)
                .param("phone", phone)
                .param("now", now)
                .update();
    }

    /** Đọc email trong transaction đang xử lý đơn hoặc vé. */
    public Optional<String> buyerEmailForOrder(UUID orderId) {
        return jdbc.sql("SELECT buyer_email FROM ticket_order WHERE id = :order")
                .param("order", orderId).query(String.class).optional();
    }

    /** Cho phép xử lý review sau khi đóng bán nhưng không phát vé cho suất đã kết thúc hoặc huỷ. */
    public boolean canIssueReviewedOrder(UUID showtimeId, OffsetDateTime now) {
        return jdbc.sql("SELECT cancelled_at IS NULL AND ends_at > :now FROM showtime WHERE id = :showtime")
                .param("showtime", showtimeId).param("now", now).query(Boolean.class).optional().orElse(false);
    }

    /** Lấy lại đúng ghế cũ bằng UPDATE có điều kiện, khoá theo seat_id và trả số ghế đã bán. */
    public int reclaimReviewSeats(UUID orderId, UUID showtimeId, OffsetDateTime now) {
        return jdbc.sql("""
                        UPDATE seat_inventory si SET status = 'SOLD', order_id = :order, held_until = NULL
                        FROM (
                            SELECT showtime_id, seat_id FROM seat_inventory
                            WHERE showtime_id = :showtime AND seat_id IN (
                                SELECT seat_id FROM order_seat WHERE order_id = :order
                            ) ORDER BY seat_id FOR UPDATE
                        ) locked
                        WHERE si.showtime_id = locked.showtime_id AND si.seat_id = locked.seat_id
                          AND (si.status = 'AVAILABLE' OR (si.status = 'HELD' AND si.held_until < :now))
                        RETURNING si.seat_id
                        """)
                .param("order", orderId).param("showtime", showtimeId).param("now", now)
                .query(UUID.class).list().size();
    }

    /** Cộng sold từng khu theo thứ tự zone_id; caller rollback toàn bộ khi một khu không đủ chỗ. */
    public boolean reclaimReviewStanding(UUID orderId, UUID showtimeId) {
        var quantities = jdbc.sql("""
                        SELECT zone_id, quantity::integer AS quantity FROM order_standing
                        WHERE order_id = :order ORDER BY zone_id
                        """)
                .param("order", orderId).query((rs, row) -> new StandingQuantity(
                        rs.getObject("zone_id", UUID.class), rs.getInt("quantity"))).list();
        for (var item : quantities) {
            int updated = jdbc.sql("""
                            UPDATE standing_inventory SET sold = sold + :quantity
                            WHERE showtime_id = :showtime AND zone_id = :zone
                              AND capacity - sold - held >= :quantity
                            """)
                    .param("showtime", showtimeId).param("zone", item.zoneId())
                    .param("quantity", item.quantity()).update();
            if (updated != 1) {
                return false;
            }
        }
        return true;
    }

    /** Đánh dấu từ chối / hoàn thủ công, không nhả lại tồn kho của đơn NEEDS_REVIEW. */
    public void markRejected(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE ticket_order SET status = 'REJECTED', updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public List<OrderLine> orderLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT os.seat_id AS id, os.showtime_id, s.zone_id, os.seat_id, 1 AS quantity
                        FROM order_seat os
                        JOIN seat s ON s.id = os.seat_id
                        WHERE os.order_id = :order
                        UNION ALL
                        SELECT ost.zone_id AS id, ost.showtime_id, ost.zone_id, NULL::uuid AS seat_id, ost.quantity::integer
                        FROM order_standing ost
                        WHERE ost.order_id = :order
                        ORDER BY seat_id NULLS LAST, zone_id
                        """)
                .param("order", orderId)
                .query((rs, row) -> new OrderLine(
                        rs.getObject("id", UUID.class),
                        rs.getObject("showtime_id", UUID.class),
                        rs.getObject("zone_id", UUID.class),
                        rs.getObject("seat_id", UUID.class),
                        rs.getInt("quantity")))
                .list();
    }

    public boolean lockSeatsForSale(UUID orderId, UUID showtimeId) {
        return jdbc.sql("""
                        WITH locked AS MATERIALIZED (
                            SELECT seat_id, status, order_id FROM seat_inventory
                            WHERE showtime_id = :showtime AND seat_id IN (
                                SELECT seat_id FROM order_seat WHERE order_id = :order
                            ) ORDER BY seat_id FOR UPDATE
                        )
                        SELECT count(*) FILTER (WHERE status = 'HELD' AND order_id = :order) = (
                            SELECT count(*) FROM order_seat WHERE order_id = :order
                        ) FROM locked
                        """)
                .param("order", orderId).param("showtime", showtimeId).query(Boolean.class).single();
    }

    public boolean lockStandingForSale(UUID orderId, UUID showtimeId) {
        return jdbc.sql("""
                        WITH items AS (
                            SELECT zone_id, quantity::integer AS quantity FROM order_standing
                            WHERE order_id = :order
                        ), locked AS MATERIALIZED (
                            SELECT zone_id, held FROM standing_inventory
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
                        UPDATE seat_inventory SET status = 'SOLD', held_until = NULL
                        WHERE order_id = :order AND status = 'HELD'
                        """)
                .param("order", orderId).update();
    }

    public boolean sellStanding(UUID orderId, UUID showtimeId) {
        return jdbc.sql("""
                        WITH items AS (
                            SELECT zone_id, quantity::integer AS quantity FROM order_standing
                            WHERE order_id = :order
                        ), sold AS (
                            UPDATE standing_inventory stock
                            SET held = stock.held - items.quantity, sold = stock.sold + items.quantity
                            FROM items
                            WHERE stock.showtime_id = :showtime AND stock.zone_id = items.zone_id
                              AND stock.held >= items.quantity
                              AND NOT EXISTS (
                                  SELECT 1 FROM items requested LEFT JOIN standing_inventory available
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
        jdbc.sql("UPDATE ticket_order SET status = 'PAID', paid_at = :now, updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public void markNeedsReview(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE ticket_order SET status = 'NEEDS_REVIEW', updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public List<OrderPosition> listMyOrders(UUID userId, String status, OrderPosition after, int limit) {
        var query = jdbc.sql("""
                        SELECT id, created_at FROM ticket_order WHERE user_id = :user
                        """
                        + (status == null ? "" : " AND status = CAST(:status AS order_status)")
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

    public List<OrderPosition> adminListOrders(String status, UUID eventId, UUID showtimeId, String q,
                                               OrderPosition after, int limit) {
        var query = jdbc.sql("""
                        SELECT o.id, o.created_at FROM ticket_order o JOIN showtime s ON s.id = o.showtime_id
                        WHERE true
                        """
                        + (status == null ? "" : " AND o.status = CAST(:status AS order_status)")
                        + (eventId == null ? "" : " AND s.event_id = :event")
                        + (showtimeId == null ? "" : " AND o.showtime_id = :showtime")
                        + (q == null ? "" : " AND (o.code = upper(:q)::bpchar OR lower(o.buyer_email) = lower(:q) OR o.buyer_phone = :q)")
                        + (after == null ? "" : " AND (o.created_at, o.id) < (:created, :id)")
                        + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit")
                .param("limit", limit);
        if (status != null) {
            query.param("status", status);
        }
        if (eventId != null) {
            query.param("event", eventId);
        }
        if (showtimeId != null) {
            query.param("showtime", showtimeId);
        }
        if (q != null) {
            query.param("q", q);
        }
        if (after != null) {
            query.param("created", after.createdAt()).param("id", after.id());
        }
        return query.query((rs, row) -> new OrderPosition(rs.getObject("id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public void cancelOrder(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE ticket_order SET status = 'CANCELLED', updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public Optional<Sale> findSale(UUID showtimeId, OffsetDateTime now) {
        return jdbc.sql("""
                        WITH locked_event AS MATERIALIZED (
                            SELECT e.id, e.status::text AS status, e.max_tickets_per_order, e.visible
                            FROM event e JOIN showtime source ON source.event_id = e.id
                            WHERE source.id = :showtime
                            FOR SHARE OF e
                        )
                        SELECT s.cancelled_at, s.sale_opens_at, s.sale_closes_at, s.ends_at,
                               e.status AS event_status, e.max_tickets_per_order, e.visible
                        FROM locked_event e JOIN showtime s ON s.event_id = e.id
                        WHERE s.id = :showtime
                        FOR SHARE OF s
                        """)
                .param("showtime", showtimeId)
                .query((rs, row) -> {
                    ShowtimeStatus computed = ShowtimeStatus.of(
                            rs.getString("event_status"),
                            rs.getObject("cancelled_at", OffsetDateTime.class),
                            rs.getObject("sale_opens_at", OffsetDateTime.class),
                            rs.getObject("sale_closes_at", OffsetDateTime.class),
                            rs.getObject("ends_at", OffsetDateTime.class),
                            now);
                    return new Sale(computed.name(), computed == ShowtimeStatus.ON_SALE && rs.getBoolean("visible"),
                            rs.getInt("max_tickets_per_order"));
                }).optional();
    }

    public Optional<PendingOrder> lockPendingOrder(UUID userId, UUID showtimeId) {
        return jdbc.sql("""
                        SELECT id, user_id, showtime_id, expires_at FROM ticket_order
                        WHERE user_id = :user AND showtime_id = :showtime AND status = 'PENDING' FOR UPDATE
                        """)
                .param("user", userId).param("showtime", showtimeId)
                .query((rs, row) -> new PendingOrder(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("showtime_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class))).optional();
    }

    public List<PendingOrder> lockOverduePendingOrders(OffsetDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id, user_id, showtime_id, expires_at FROM ticket_order
                        WHERE status = 'PENDING' AND expires_at <= :now
                        ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
                        """)
                .param("now", now).param("limit", limit)
                .query((rs, row) -> new PendingOrder(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("showtime_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class))).list();
    }

    public void lockReplacementInventory(PendingOrder old, Collection<UUID> seatIds, UUID zoneId) {
        jdbc.sql("""
                        SELECT seat_id FROM seat_inventory
                        WHERE showtime_id = :showtime AND (
                            (order_id = :order AND status = 'HELD') OR seat_id = ANY(:ids)
                        ) ORDER BY seat_id FOR UPDATE
                        """)
                .param("showtime", old.showtimeId()).param("order", old.id())
                .param("ids", new SqlArrayValue("uuid", seatIds.toArray())).query(UUID.class).list();
        jdbc.sql("""
                        SELECT zone_id FROM standing_inventory
                        WHERE showtime_id = :showtime AND (zone_id = :zone OR zone_id IN (
                            SELECT zone_id FROM order_standing WHERE order_id = :order
                        )) ORDER BY zone_id FOR UPDATE
                        """)
                .param("showtime", old.showtimeId()).param("order", old.id())
                .param("zone", zoneId, Types.OTHER).query(UUID.class).list();
    }

    public List<HoldItem> seatItems(UUID showtimeId, Collection<UUID> seatIds, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT si.seat_id, si.zone_id, sk.id AS sku_id,
                               ez.ticket_type_id,
                               ttp.id AS ticket_type_price_id, ttp.price
                        FROM seat_inventory si
                        JOIN showtime st ON st.id = si.showtime_id
                        JOIN zone z ON z.id = si.zone_id AND z.seat_map_id = st.seat_map_id
                        LEFT JOIN event_zone ez ON ez.event_id = st.event_id AND ez.zone_id = z.id
                        LEFT JOIN sku sk ON sk.event_id = st.event_id
                                        AND sk.ticket_type_id = ez.ticket_type_id
                                        AND sk.seat_id = si.seat_id
                        LEFT JOIN LATERAL (
                            SELECT id, price FROM ticket_type_price
                            WHERE ticket_type_id = ez.ticket_type_id AND effective_from <= :now
                            ORDER BY effective_from DESC LIMIT 1
                        ) ttp ON true
                        WHERE si.showtime_id = :showtime AND si.seat_id = ANY(:ids) AND z.kind = 'SEATED'
                        ORDER BY si.seat_id
                        """)
                .param("showtime", showtimeId)
                .param("ids", new SqlArrayValue("uuid", seatIds.toArray()))
                .param("now", now)
                .query((rs, row) -> new HoldItem(
                        rs.getObject("seat_id", UUID.class),
                        rs.getObject("zone_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getObject("ticket_type_price_id", UUID.class),
                        1,
                        rs.getLong("price")))
                .list();
    }

    public Optional<HoldItem> standingItem(UUID showtimeId, UUID zoneId, int quantity, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT z.id AS zone_id, sk.id AS sku_id,
                               ez.ticket_type_id,
                               ttp.id AS ticket_type_price_id, ttp.price
                        FROM standing_inventory stock
                        JOIN showtime st ON st.id = stock.showtime_id
                        JOIN zone z ON z.id = stock.zone_id AND z.seat_map_id = st.seat_map_id
                        LEFT JOIN event_zone ez ON ez.event_id = st.event_id AND ez.zone_id = z.id
                        LEFT JOIN sku sk ON sk.event_id = st.event_id
                                        AND sk.ticket_type_id = ez.ticket_type_id
                                        AND sk.zone_id = z.id
                        LEFT JOIN LATERAL (
                            SELECT id, price FROM ticket_type_price
                            WHERE ticket_type_id = ez.ticket_type_id AND effective_from <= :now
                            ORDER BY effective_from DESC LIMIT 1
                        ) ttp ON true
                        WHERE stock.showtime_id = :showtime AND stock.zone_id = :zone AND z.kind = 'STANDING'
                        """)
                .param("showtime", showtimeId)
                .param("zone", zoneId)
                .param("now", now)
                .query((rs, row) -> new HoldItem(
                        null,
                        rs.getObject("zone_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getObject("ticket_type_price_id", UUID.class),
                        quantity,
                        rs.getLong("price")))
                .optional();
    }

    public void lockReholdScope(UUID userId, UUID showtimeId) {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) locked")
                .param("key", "hold-cooldown:" + userId + ":" + showtimeId).query(Integer.class).single();
    }

    public Optional<OffsetDateTime> lastReleaseOfOverheldSeats(UUID userId, UUID showtimeId, List<UUID> seatIds,
                                                               OffsetDateTime since, int maxReleases) {
        var rows = jdbc.sql("""
                        SELECT max(last_release) FROM (
                            SELECT os.seat_id,
                                   max(CASE WHEN o.status = 'EXPIRED' THEN o.expires_at ELSE o.updated_at END) AS last_release
                            FROM ticket_order o
                            JOIN order_seat os ON os.order_id = o.id
                            WHERE o.user_id = :user AND o.showtime_id = :showtime
                              AND os.seat_id = ANY(:ids) AND o.status IN ('EXPIRED', 'CANCELLED')
                              AND CASE WHEN o.status = 'EXPIRED' THEN o.expires_at ELSE o.updated_at END >= :since
                            GROUP BY os.seat_id HAVING count(*) >= :max
                        ) overheld
                        """)
                .param("user", userId).param("showtime", showtimeId)
                .param("ids", new SqlArrayValue("uuid", seatIds.toArray()))
                .param("since", since).param("max", maxReleases)
                .query(OffsetDateTime.class).list();
        return rows.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public Optional<UUID> insertOrder(String code, UUID userId, UUID showtimeId, long total,
                                      OffsetDateTime expiresAt, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO ticket_order (code, user_id, showtime_id, status, total_amount, expires_at,
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
                        UPDATE seat_inventory si SET status = 'HELD', order_id = :order, held_until = :expires
                        FROM (
                            SELECT seat_id FROM seat_inventory
                            WHERE showtime_id = :showtime AND seat_id = ANY(:ids)
                              AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < :now))
                            ORDER BY seat_id FOR UPDATE
                        ) f
                        WHERE si.showtime_id = :showtime AND si.seat_id = f.seat_id RETURNING si.seat_id
                        """)
                .param("showtime", showtimeId).param("ids", new SqlArrayValue("uuid", seatIds.toArray()))
                .param("order", orderId).param("expires", expiresAt).param("now", now)
                .query(UUID.class).list();
    }

    public boolean holdStanding(UUID showtimeId, UUID zoneId, int quantity) {
        return jdbc.sql("""
                        UPDATE standing_inventory SET held = held + :n
                        WHERE showtime_id = :showtime AND zone_id = :zone AND capacity - sold - held >= :n
                        """)
                .param("showtime", showtimeId).param("zone", zoneId).param("n", quantity).update() == 1;
    }

    public int seatsLeft(UUID showtimeId, UUID zoneId) {
        return jdbc.sql("""
                        SELECT GREATEST(capacity - sold - held, 0) FROM standing_inventory
                        WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("showtime", showtimeId).param("zone", zoneId).query(Integer.class).single();
    }

    public void insertItem(UUID orderId, UUID showtimeId, HoldItem item) {
        if (item.seatId() != null) {
            jdbc.sql("""
                            INSERT INTO order_seat (
                                order_id, showtime_id, seat_id,
                                sku_id, ticket_type_id, ticket_type_price_id, unit_price
                            )
                            VALUES (:order, :showtime, :seat, :sku, :ticketType, :ticketTypePrice, :price)
                            """)
                    .param("order", orderId)
                    .param("showtime", showtimeId)
                    .param("seat", item.seatId())
                    .param("sku", item.skuId(), Types.OTHER)
                    .param("ticketType", item.ticketTypeId(), Types.OTHER)
                    .param("ticketTypePrice", item.skuPriceId(), Types.OTHER)
                    .param("price", item.unitPrice())
                    .update();
        } else {
            jdbc.sql("""
                            INSERT INTO order_standing (
                                order_id, showtime_id, zone_id,
                                sku_id, ticket_type_id, ticket_type_price_id, quantity, unit_price
                            )
                            VALUES (:order, :showtime, :zone, :sku, :ticketType, :ticketTypePrice, :quantity, :price)
                            """)
                    .param("order", orderId)
                    .param("showtime", showtimeId)
                    .param("zone", item.zoneId())
                    .param("sku", item.skuId(), Types.OTHER)
                    .param("ticketType", item.ticketTypeId(), Types.OTHER)
                    .param("ticketTypePrice", item.skuPriceId(), Types.OTHER)
                    .param("quantity", item.quantity())
                    .param("price", item.unitPrice())
                    .update();
        }
    }

    public void insertHistory(UUID orderId, String from, String to, UUID actorId, OffsetDateTime now) {
        insertHistory(orderId, from, to, actorId, null, now);
    }

    public void insertHistory(UUID orderId, String from, String to, UUID actorId, String reason, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO order_status_history (order_id, from_status, to_status, actor_id, reason, at)
                        VALUES (:order, CAST(:from AS order_status), CAST(:to AS order_status), :actor, :reason, :now)
                        """)
                .param("order", orderId).param("from", from, Types.VARCHAR).param("to", to)
                .param("actor", actorId, Types.OTHER).param("reason", reason, Types.VARCHAR).param("now", now).update();
    }

    public void releaseSeats(UUID orderId) {
        jdbc.sql("""
                        UPDATE seat_inventory si SET status = 'AVAILABLE', order_id = NULL, held_until = NULL
                        FROM (
                            SELECT showtime_id, seat_id FROM seat_inventory
                            WHERE order_id = :order AND status = 'HELD' ORDER BY seat_id FOR UPDATE
                        ) f
                        WHERE si.showtime_id = f.showtime_id AND si.seat_id = f.seat_id
                          AND si.order_id = :order AND si.status = 'HELD'
                        """)
                .param("order", orderId).update();
    }

    public void releaseStanding(UUID orderId, UUID showtimeId) {
        boolean skipped = jdbc.sql("""
                        WITH locked AS MATERIALIZED (
                            SELECT stock.showtime_id, stock.zone_id, items.quantity
                            FROM standing_inventory stock JOIN (
                                SELECT zone_id, quantity::integer AS quantity FROM order_standing
                                WHERE order_id = :order
                            ) items ON items.zone_id = stock.zone_id
                            WHERE stock.showtime_id = :showtime
                            ORDER BY stock.zone_id FOR UPDATE OF stock
                        ), released AS (
                            UPDATE standing_inventory stock SET held = stock.held - items.quantity
                            FROM locked items
                            WHERE stock.showtime_id = items.showtime_id AND stock.zone_id = items.zone_id
                              AND stock.held >= items.quantity
                            RETURNING stock.zone_id
                        )
                        SELECT (SELECT count(*) FROM released) < (SELECT count(*) FROM locked)
                        """)
                .param("order", orderId).param("showtime", showtimeId).query(Boolean.class).single();
        if (skipped) {
            log.atError().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue("showtime_id", showtimeId)
                    .log("held khu đứng lệch, không trả held");
        }
    }

    public void expireOrder(UUID orderId, OffsetDateTime now) {
        jdbc.sql("UPDATE ticket_order SET status = 'EXPIRED', updated_at = :now WHERE id = :order")
                .param("order", orderId).param("now", now).update();
    }

    public record Sale(String status, boolean onSale, int maxTicketsPerOrder) {}

    public record OrderPosition(UUID id, OffsetDateTime createdAt) {}

    public record PendingOrder(UUID id, UUID userId, UUID showtimeId, OffsetDateTime expiresAt) {}

    public record HoldItem(
            UUID seatId,
            UUID zoneId,
            UUID skuId,
            UUID ticketTypeId,
            UUID skuPriceId,
            int quantity,
            long unitPrice
    ) {
        public HoldItem(UUID seatId, UUID zoneId, UUID skuPriceId, int quantity, long unitPrice) {
            this(seatId, zoneId, null, null, skuPriceId, quantity, unitPrice);
        }
    }

    private record StandingQuantity(UUID zoneId, int quantity) {}
}
