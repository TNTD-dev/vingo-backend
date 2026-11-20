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
                    SELECT 1 FROM event_zone WHERE sku_id = :skuId
                )
                OR EXISTS (
                    SELECT 1 FROM order_seat os
                    JOIN sku_price sp ON sp.id = os.sku_price_id
                    WHERE sp.sku_id = :skuId
                )
                OR EXISTS (
                    SELECT 1 FROM order_standing ost
                    JOIN sku_price sp ON sp.id = ost.sku_price_id
                    WHERE sp.sku_id = :skuId
                )
                """)
                .param("skuId", skuId)
                .query(Boolean.class)
                .single();
    }

    public UUID insertTier(UUID eventId, PriceTierChange input, UUID actorId, OffsetDateTime now) {
        UUID skuId = jdbc.sql("""
                INSERT INTO sku (event_id, name, color)
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
                UPDATE sku
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
                SELECT price FROM sku_price
                WHERE sku_id = :skuId AND effective_from <= :now
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
                INSERT INTO sku_price (sku_id, price, effective_from, created_by, reason, created_at)
                VALUES (:skuId, :price, :effectiveFrom, :createdBy, :reason, :createdAt)
                RETURNING id, sku_id, price, effective_from, created_by, reason, created_at
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
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sku WHERE id = :skuId AND event_id = :eventId)")
                .param("skuId", skuId)
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    public boolean skuPriceTimestampExists(UUID skuId, OffsetDateTime effectiveFrom) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sku_price WHERE sku_id = :skuId AND effective_from = :effectiveFrom)")
                .param("skuId", skuId)
                .param("effectiveFrom", effectiveFrom)
                .query(Boolean.class)
                .single();
    }

    public void deleteTier(UUID eventId, UUID skuId) {
        jdbc.sql("DELETE FROM sku_price WHERE sku_id = :skuId")
                .param("skuId", skuId)
                .update();
        int changed = jdbc.sql("DELETE FROM sku WHERE event_id = :eventId AND id = :skuId")
                .param("eventId", eventId)
                .param("skuId", skuId)
                .update();
        requireOneRow(changed);
    }

    public void renameTierTemporarily(UUID eventId, UUID skuId, String temporaryName) {
        int changed = jdbc.sql("""
                UPDATE sku SET name = :name
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

    public void replaceZonePrices(UUID eventId, UUID seatMapId, List<ZonePriceChange> input) {
        jdbc.sql("DELETE FROM event_zone WHERE event_id = :eventId")
                .param("eventId", eventId)
                .update();

        for (var item : input) {
            jdbc.sql("""
                    INSERT INTO event_zone (event_id, zone_id, seat_map_id, sku_id, display_name)
                    VALUES (:eventId, :zoneId, :seatMapId, :skuId, :displayName)
                    """)
                    .param("eventId", eventId)
                    .param("zoneId", item.zoneId())
                    .param("seatMapId", seatMapId)
                    .param("skuId", item.priceTierId())
                    .param("displayName", item.displayName() == null ? null : item.displayName().strip(), Types.VARCHAR)
                    .update();
        }
    }

    private static void requireOneRow(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Không cập nhật được bản ghi đã kiểm tra và khóa.");
        }
    }
}
