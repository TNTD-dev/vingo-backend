package vingo.catalog.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.catalog.PriceTierChange;
import vingo.catalog.ZonePriceChange;
@Repository
public class EventPricingRepository {

    private final JdbcClient jdbc;

    public EventPricingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasStartedSalesOrOrders(
            UUID eventId,
            OffsetDateTime now
    ) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM showtime
                    WHERE event_id = :eventId
                      AND status <> 'CANCELLED'
                      AND sale_opens_at <= :now
                )
                OR EXISTS (
                    SELECT 1
                    FROM orders o
                    JOIN showtime s ON s.id = o.showtime_id
                    WHERE s.event_id = :eventId
                )
                """)
                .param("eventId", eventId)
                .param("now", now)
                .query(Boolean.class)
                .single();
    }

    public boolean isTierReferenced(UUID tierId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM zone_price
                    WHERE price_tier_id = :tierId
                )
                OR EXISTS (
                    SELECT 1
                    FROM order_item
                    WHERE price_tier_id = :tierId
                )
                """)
                .param("tierId", tierId)
                .query(Boolean.class)
                .single();
    }

    public UUID insertTier(
            UUID eventId,
            PriceTierChange input
    ) {
        return jdbc.sql("""
                INSERT INTO price_tier (
                    event_id, name, price, color
                )
                VALUES (
                    :eventId, :name, :price, :color
                )
                RETURNING id
                """)
                .param("eventId", eventId)
                .param("name", input.name())
                .param("price", input.price())
                .param("color", input.color(), Types.VARCHAR)
                .query(UUID.class)
                .single();
    }

    public void updateTier(
            UUID eventId,
            PriceTierChange input
    ) {
        int changed = jdbc.sql("""
                UPDATE price_tier
                SET name = :name,
                    price = :price,
                    color = :color
                WHERE event_id = :eventId
                  AND id = :tierId
                """)
                .param("eventId", eventId)
                .param("tierId", input.id())
                .param("name", input.name())
                .param("price", input.price())
                .param("color", input.color(), Types.VARCHAR)
                .update();

        requireOneRow(changed);
    }

    public void deleteTier(UUID eventId, UUID tierId) {
        int changed = jdbc.sql("""
                DELETE FROM price_tier
                WHERE event_id = :eventId
                  AND id = :tierId
                """)
                .param("eventId", eventId)
                .param("tierId", tierId)
                .update();

        requireOneRow(changed);
    }

    public void renameTierTemporarily(
            UUID eventId,
            UUID tierId,
            String temporaryName
    ) {
        int changed = jdbc.sql("""
                UPDATE price_tier
                SET name = :name
                WHERE event_id = :eventId
                  AND id = :tierId
                """)
                .param("eventId", eventId)
                .param("tierId", tierId)
                .param("name", temporaryName)
                .update();

        requireOneRow(changed);
    }

    public void touchEvent(
            UUID eventId,
            OffsetDateTime now
    ) {
        int changed = jdbc.sql("""
                UPDATE event
                SET updated_at = :now
                WHERE id = :eventId
                """)
                .param("eventId", eventId)
                .param("now", now)
                .update();

        requireOneRow(changed);
    }

    private static void requireOneRow(int changed) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "Không cập nhật được bản ghi đã kiểm tra và khóa."
            );
        }
    }
    public Set<UUID> findUsedZoneIds(UUID eventId) {
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT z.id
                FROM zone z
                JOIN showtime s
                ON s.seat_layout_id = z.seat_layout_id
                WHERE s.event_id = :eventId
                AND s.status <> 'CANCELLED'
                """)
                .param("eventId", eventId)
                .query(UUID.class)
                .list());
    }

    public void replaceZonePrices(
            UUID eventId,
            List<ZonePriceChange> input
    ) {
        jdbc.sql("""
                DELETE FROM zone_price
                WHERE event_id = :eventId
                """)
                .param("eventId", eventId)
                .update();

        for (var item : input) {
            jdbc.sql("""
                    INSERT INTO zone_price (
                        event_id, zone_id, price_tier_id
                    )
                    VALUES (
                        :eventId, :zoneId, :tierId
                    )
                    """)
                    .param("eventId", eventId)
                    .param("zoneId", item.zoneId())
                    .param("tierId", item.priceTierId())
                    .update();
        }
    }
}
