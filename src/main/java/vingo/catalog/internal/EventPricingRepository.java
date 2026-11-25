package vingo.catalog.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.catalog.EventDetails.SkuPriceRow;
import vingo.catalog.PriceTierChange;
import vingo.catalog.ZonePriceChange;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Repository
public class EventPricingRepository {

    private final JdbcClient jdbc;

    public EventPricingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasStartedSalesOrOrders(UUID eventId, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM showtime s
                    JOIN event e ON e.id = s.event_id
                    WHERE s.event_id = :eventId
                      AND e.status = 'PUBLISHED'
                      AND s.cancelled_at IS NULL
                      AND s.sale_opens_at <= :now
                )
                OR EXISTS (
                    SELECT 1
                    FROM ticket_order o
                    JOIN showtime s ON s.id = o.showtime_id
                    WHERE s.event_id = :eventId
                )
                """)
                .param("eventId", eventId)
                .param("now", now)
                .query(Boolean.class)
                .single();
    }

    public boolean isTierReferenced(UUID skuId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM event_zone WHERE ticket_type_id = :skuId
                )
                OR EXISTS (
                    SELECT 1 FROM order_seat os
                    JOIN ticket_type_price ttp ON ttp.id = os.ticket_type_price_id
                    WHERE ttp.ticket_type_id = :skuId
                )
                OR EXISTS (
                    SELECT 1 FROM order_standing ost
                    JOIN ticket_type_price ttp ON ttp.id = ost.ticket_type_price_id
                    WHERE ttp.ticket_type_id = :skuId
                )
                """)
                .param("skuId", skuId)
                .query(Boolean.class)
                .single();
    }

    public UUID insertTier(UUID eventId, PriceTierChange input, UUID actorId, OffsetDateTime now) {
        UUID skuId = jdbc.sql("""
                INSERT INTO ticket_type (event_id, name, color)
                VALUES (:eventId, :name, :color)
                RETURNING id
                """)
                .param("eventId", eventId)
                .param("name", input.name())
                .param("color", input.color())
                .query(UUID.class)
                .single();

        insertSkuPrice(skuId, input.price(), now, actorId, null, now);
        return skuId;
    }

    public void updateTier(UUID eventId, PriceTierChange input, UUID actorId, OffsetDateTime now) {
        int changed = jdbc.sql("""
                UPDATE ticket_type
                SET name = :name,
                    color = :color
                WHERE event_id = :eventId
                  AND id = :skuId
                """)
                .param("eventId", eventId)
                .param("skuId", input.id())
                .param("name", input.name())
                .param("color", input.color())
                .update();
        requireOneRow(changed);

        Long currentPrice = jdbc.sql("""
                SELECT price FROM ticket_type_price
                WHERE ticket_type_id = :skuId AND effective_from <= :now
                ORDER BY effective_from DESC LIMIT 1
                """).param("skuId", input.id()).param("now", now).query(Long.class).optional().orElse(null);

        if (!input.price().equals(currentPrice)) {
            if (skuPriceTimestampExists(input.id(), now)) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "Đã có mức giá ở mốc thời gian hiệu lực này.");
            }
            insertSkuPrice(input.id(), input.price(), now, actorId, null, now);
        }
    }

    public SkuPriceRow insertSkuPrice(
            UUID skuId, long price, OffsetDateTime effectiveFrom,
            UUID actorId, String reason, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO ticket_type_price (ticket_type_id, price, effective_from, created_by, reason, created_at)
                VALUES (:skuId, :price, :effectiveFrom, :createdBy, :reason, :createdAt)
                RETURNING id, ticket_type_id AS sku_id, price, effective_from, created_by, reason, created_at
                """)
                .param("skuId", skuId)
                .param("price", price)
                .param("effectiveFrom", effectiveFrom)
                .param("createdBy", actorId)
                .param("reason", reason, Types.VARCHAR)
                .param("createdAt", now)
                .query((rs, row) -> new SkuPriceRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getLong("price"),
                        rs.getObject("effective_from", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }

    public boolean skuExistsInEvent(UUID eventId, UUID skuId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ticket_type WHERE id = :skuId AND event_id = :eventId)")
                .param("skuId", skuId)
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    public boolean skuPriceTimestampExists(UUID skuId, OffsetDateTime effectiveFrom) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ticket_type_price WHERE ticket_type_id = :skuId AND effective_from = :effectiveFrom)")
                .param("skuId", skuId)
                .param("effectiveFrom", effectiveFrom)
                .query(Boolean.class)
                .single();
    }

    public void deleteTier(UUID eventId, UUID skuId) {
        jdbc.sql("DELETE FROM sku WHERE event_id = :eventId AND ticket_type_id = :skuId")
                .param("eventId", eventId)
                .param("skuId", skuId)
                .update();
        jdbc.sql("DELETE FROM ticket_type_price WHERE ticket_type_id = :skuId")
                .param("skuId", skuId)
                .update();
        int changed = jdbc.sql("DELETE FROM ticket_type WHERE event_id = :eventId AND id = :skuId")
                .param("eventId", eventId)
                .param("skuId", skuId)
                .update();
        requireOneRow(changed);
    }

    public void renameTierTemporarily(UUID eventId, UUID skuId, String temporaryName) {
        int changed = jdbc.sql("""
                UPDATE ticket_type SET name = :name
                WHERE event_id = :eventId AND id = :skuId
                """)
                .param("eventId", eventId)
                .param("skuId", skuId)
                .param("name", temporaryName)
                .update();
        requireOneRow(changed);
    }

    public void touchEvent(UUID eventId, OffsetDateTime now) {
        int changed = jdbc.sql("UPDATE event SET updated_at = :now WHERE id = :eventId")
                .param("eventId", eventId)
                .param("now", now)
                .update();
        requireOneRow(changed);
    }

    public Optional<UUID> findEventSeatMapId(UUID eventId) {
        return jdbc.sql("SELECT seat_map_id FROM event WHERE id = :eventId")
                .param("eventId", eventId)
                .query(UUID.class)
                .optional();
    }

    public Set<UUID> findUsedZoneIds(UUID eventId) {
        return Set.copyOf(jdbc.sql("""
                SELECT z.id
                FROM zone z
                JOIN event e ON e.seat_map_id = z.seat_map_id
                WHERE e.id = :eventId
                """)
                .param("eventId", eventId)
                .query(UUID.class)
                .list());
    }

    public record ZoneShape(UUID id, String name, String kind, int seatCount) {}

    public List<ZoneShape> findLayoutZoneShapes(UUID eventId) {
        return jdbc.sql("""
                SELECT z.id, z.name, z.kind::text AS kind,
                       CASE WHEN z.kind = 'SEATED'
                            THEN (SELECT count(*)::integer FROM seat s WHERE s.zone_id = z.id AND s.seat_map_id = z.seat_map_id)
                            ELSE 1
                       END AS sku_count
                FROM zone z
                JOIN event e ON e.seat_map_id = z.seat_map_id
                WHERE e.id = :eventId
                ORDER BY z.name, z.id
                """)
                .param("eventId", eventId)
                .query((rs, row) -> new ZoneShape(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getInt("sku_count")))
                .list();
    }

    public void replaceZonePrices(UUID eventId, UUID seatMapId, List<ZonePriceChange> input) {
        if (input.isEmpty()) {
            jdbc.sql("""
                    DELETE FROM sku s
                    WHERE s.event_id = :eventId
                      AND NOT EXISTS (SELECT 1 FROM order_seat os WHERE os.sku_id = s.id)
                      AND NOT EXISTS (SELECT 1 FROM order_standing ost WHERE ost.sku_id = s.id)
                    """)
                    .param("eventId", eventId)
                    .update();
            jdbc.sql("DELETE FROM event_zone WHERE event_id = :eventId")
                    .param("eventId", eventId)
                    .update();
            return;
        }

        // Xóa các khu không còn nằm trong cấu hình mới hoặc đổi hạng vé; giữ nguyên SKU của cặp (khu, hạng) không đổi
        var existing = jdbc.sql("""
                SELECT zone_id, ticket_type_id
                FROM event_zone
                WHERE event_id = :eventId
                """)
                .param("eventId", eventId)
                .query((rs, row) -> java.util.Map.entry(
                        rs.getObject("zone_id", UUID.class),
                        rs.getObject("ticket_type_id", UUID.class)))
                .list();

        java.util.Map<UUID, UUID> desiredByZone = new java.util.HashMap<>();
        for (var item : input) {
            desiredByZone.put(item.zoneId(), item.priceTierId());
        }

        for (var oldEntry : existing) {
            UUID oldZoneId = oldEntry.getKey();
            UUID oldTierId = oldEntry.getValue();
            UUID desiredTierId = desiredByZone.get(oldZoneId);
            if (!oldTierId.equals(desiredTierId)) {
                jdbc.sql("""
                        DELETE FROM sku s
                        USING seat se
                        WHERE s.event_id = :eventId
                          AND s.ticket_type_id = :ticketTypeId
                          AND s.seat_id = se.id
                          AND se.zone_id = :zoneId
                          AND NOT EXISTS (SELECT 1 FROM order_seat os WHERE os.sku_id = s.id)
                        """)
                        .param("eventId", eventId)
                        .param("ticketTypeId", oldTierId)
                        .param("zoneId", oldZoneId)
                        .update();
                jdbc.sql("""
                        DELETE FROM sku s
                        WHERE s.event_id = :eventId
                          AND s.ticket_type_id = :ticketTypeId
                          AND s.zone_id = :zoneId
                          AND NOT EXISTS (SELECT 1 FROM order_standing ost WHERE ost.sku_id = s.id)
                        """)
                        .param("eventId", eventId)
                        .param("ticketTypeId", oldTierId)
                        .param("zoneId", oldZoneId)
                        .update();
                jdbc.sql("DELETE FROM event_zone WHERE event_id = :eventId AND zone_id = :zoneId")
                        .param("eventId", eventId)
                        .param("zoneId", oldZoneId)
                        .update();
            }
        }

        for (var item : input) {
            String displayName = item.displayName() == null ? null : item.displayName().strip();
            jdbc.sql("""
                    INSERT INTO event_zone (event_id, zone_id, seat_map_id, ticket_type_id, display_name)
                    VALUES (:eventId, :zoneId, :seatMapId, :ticketTypeId, :displayName)
                    ON CONFLICT (event_id, zone_id) DO UPDATE
                        SET seat_map_id    = EXCLUDED.seat_map_id,
                            ticket_type_id = EXCLUDED.ticket_type_id,
                            display_name   = EXCLUDED.display_name
                    """)
                    .param("eventId", eventId)
                    .param("zoneId", item.zoneId())
                    .param("seatMapId", seatMapId)
                    .param("ticketTypeId", item.priceTierId())
                    .param("displayName", displayName, Types.VARCHAR)
                    .update();

            // Sinh SKU vé ngồi (1 SKU cho mỗi cặp ghế + hạng) và SKU vé đứng (1 SKU cho cặp khu + hạng)
            // Idempotent: gửi lại cùng cấu hình giữ nguyên các SKU hiện có.
            jdbc.sql("""
                    INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
                    SELECT :eventId, :ticketTypeId, :seatMapId, se.id, NULL, 'SEATED'::zone_kind
                    FROM zone z
                    JOIN seat se ON se.zone_id = z.id AND se.seat_map_id = z.seat_map_id
                    WHERE z.id = :zoneId AND z.seat_map_id = :seatMapId AND z.kind = 'SEATED'
                    ON CONFLICT (event_id, seat_id, ticket_type_id) WHERE seat_id IS NOT NULL DO NOTHING
                    """)
                    .param("eventId", eventId)
                    .param("ticketTypeId", item.priceTierId())
                    .param("seatMapId", seatMapId)
                    .param("zoneId", item.zoneId())
                    .update();

            jdbc.sql("""
                    INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
                    SELECT :eventId, :ticketTypeId, :seatMapId, NULL, z.id, 'STANDING'::zone_kind
                    FROM zone z
                    WHERE z.id = :zoneId AND z.seat_map_id = :seatMapId AND z.kind = 'STANDING'
                    ON CONFLICT (event_id, zone_id, ticket_type_id) WHERE zone_id IS NOT NULL DO NOTHING
                    """)
                    .param("eventId", eventId)
                    .param("ticketTypeId", item.priceTierId())
                    .param("seatMapId", seatMapId)
                    .param("zoneId", item.zoneId())
                    .update();
        }
    }

    private static void requireOneRow(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Không cập nhật được bản ghi đã kiểm tra và khóa.");
        }
    }
}
