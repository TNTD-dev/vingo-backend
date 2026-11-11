package vingo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;

class CatalogFixtureTest extends IntegrationTest {

    @Test
    void onSaleShowtime_createsAvailableSeatsStandingStockAndCouplePair() {
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM showtime_seat ss JOIN seat s ON s.id = ss.seat_id
                        WHERE ss.showtime_id = :showtime AND ss.status = 'AVAILABLE'
                          AND s.zone_id = :zone AND s.row_label = 'A' AND s.number BETWEEN 1 AND 10
                        """)
                .param("showtime", showtime.id()).param("zone", event.seatedZoneId())
                .query(Integer.class).single()).isEqualTo(10);
        assertThat(jdbc.sql("""
                        SELECT capacity = 5 AND sold = 0 AND held = 0
                        FROM showtime_zone_stock WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("showtime", showtime.id()).param("zone", event.standingZoneId())
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM seat_pair p
                        JOIN seat a ON a.id = p.seat_a_id JOIN seat_type ta ON ta.id = a.seat_type_id
                        JOIN seat b ON b.id = p.seat_b_id JOIN seat_type tb ON tb.id = b.seat_type_id
                        WHERE p.seat_a_id = LEAST(CAST(:nine AS uuid), CAST(:ten AS uuid))
                          AND p.seat_b_id = GREATEST(CAST(:nine AS uuid), CAST(:ten AS uuid))
                          AND p.seat_a_id < p.seat_b_id AND ta.code = 'COUPLE' AND tb.code = 'COUPLE'
                        """)
                .param("nine", event.seat(9)).param("ten", event.seat(10))
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT l.status = 'PUBLISHED' AND l.published_at IS NOT NULL
                          AND e.status = 'PUBLISHED' AND e.max_tickets_per_order = 10
                          AND v.city LIKE 'City-%'
                        FROM showtime s JOIN event e ON e.id = s.event_id
                        JOIN seat_layout l ON l.id = s.seat_layout_id
                        JOIN hall h ON h.id = s.hall_id JOIN venue v ON v.id = h.venue_id
                        WHERE s.id = :showtime
                        """)
                .param("showtime", showtime.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT t.price FROM zone_price p JOIN price_tier t ON t.id = p.price_tier_id
                        JOIN zone z ON z.id = p.zone_id
                        WHERE p.event_id = :event ORDER BY z.name
                        """)
                .param("event", event.id()).query(Long.class).list()).containsExactly(500_000L, 200_000L);
    }

    @Test
    void event_usesSpecAndCreatesIndependentCatalogEachTime() {
        var first = catalog.event();
        var spec = EventSpec.defaults().withTitle("Fixture concert").withCategory("OTHER")
                .withCity("Fixture city").withStatus("DRAFT").withMaxTicketsPerOrder(3).withStandingCapacity(8);
        var second = catalog.event(spec);
        var showtime = catalog.showtime(second, Window.draft());

        assertThat(List.of(second.id(), second.venueId(), second.hallId(), second.layoutId(),
                        second.seatedZoneId(), second.standingZoneId(), second.vipPriceTierId(), second.gaPriceTierId()))
                .doesNotContainAnyElementsOf(List.of(first.id(), first.venueId(), first.hallId(), first.layoutId(),
                        first.seatedZoneId(), first.standingZoneId(), first.vipPriceTierId(), first.gaPriceTierId()));
        assertThat(second.seats()).hasSize(10).doesNotContainAnyElementsOf(first.seats());
        assertThat(jdbc.sql("""
                        SELECT e.title, e.category, e.status, e.max_tickets_per_order, v.city, stock.capacity
                        FROM showtime s JOIN event e ON e.id = s.event_id
                        JOIN hall h ON h.id = s.hall_id JOIN venue v ON v.id = h.venue_id
                        JOIN showtime_zone_stock stock ON stock.showtime_id = s.id
                        WHERE s.id = :showtime
                        """)
                .param("showtime", showtime.id()).query().singleRow())
                .containsEntry("title", spec.title()).containsEntry("category", spec.category())
                .containsEntry("status", spec.status()).containsEntry("max_tickets_per_order", 3)
                .containsEntry("city", spec.city()).containsEntry("capacity", 8);
    }

    @Test
    void showtime_acceptsEveryWindowAndRepresentsItsIntendedState() {
        var event = catalog.event();
        var windows = List.of(Window.onSale(), Window.notOpenYet(), Window.salesClosed(),
                Window.finished(), Window.cancelled(), Window.draft());
        var expected = List.of("ON_SALE", "NOT_OPEN_YET", "SALES_CLOSED", "FINISHED", "CANCELLED", "DRAFT");

        for (int i = 0; i < windows.size(); i++) {
            var showtime = catalog.showtime(event, windows.get(i));

            assertThat(jdbc.sql("""
                            SELECT CASE
                                WHEN status IN ('CANCELLED', 'DRAFT') THEN status
                                WHEN ends_at <= :now THEN 'FINISHED'
                                WHEN sale_opens_at > :now THEN 'NOT_OPEN_YET'
                                WHEN sale_closes_at <= :now THEN 'SALES_CLOSED'
                                ELSE 'ON_SALE' END
                            FROM showtime WHERE id = :showtime
                            """)
                    .param("now", catalog.now()).param("showtime", showtime.id()).query(String.class).single())
                    .isEqualTo(expected.get(i));
        }
    }

    @Test
    void order_createsPaidAndNeedsReviewOrdersWithRequiredFields() {
        var showtime = catalog.onSaleShowtime();
        var buyer = catalog.buyer();
        var expiresAt = catalog.now().plusMinutes(10);

        assertThat(jdbc.sql("SELECT role = 'BUYER' AND status = 'ACTIVE' FROM users WHERE id = :buyer")
                .param("buyer", buyer).query(Boolean.class).single()).isTrue();
        for (String status : List.of("PAID", "NEEDS_REVIEW")) {
            var order = catalog.order(buyer, showtime.id(), status, expiresAt);

            assertThat(jdbc.sql("""
                            SELECT status = :status AND length(code) = 6
                              AND user_id = :buyer AND showtime_id = :showtime
                              AND (status <> 'PAID' OR paid_at IS NOT NULL)
                              AND (status <> 'NEEDS_REVIEW' OR review_reason IS NOT NULL)
                            FROM orders WHERE id = :order
                            """)
                    .param("status", status).param("buyer", buyer).param("showtime", showtime.id())
                    .param("order", order).query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void setters_updateSeatAndStandingStockIncludingNullableHoldFields() {
        var showtime = catalog.onSaleShowtime();
        var seat = showtime.event().seat(1);
        var heldUntil = catalog.now().plusMinutes(10);
        var order = catalog.order(catalog.buyer(), showtime.id(), "PENDING", heldUntil);

        catalog.setSeat(showtime.id(), seat, "HELD", order, heldUntil);
        catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 2, 1);

        assertThat(jdbc.sql("""
                        SELECT status = 'HELD' AND order_id = :order AND held_until IS NOT NULL
                        FROM showtime_seat WHERE showtime_id = :showtime AND seat_id = :seat
                        """)
                .param("order", order).param("showtime", showtime.id()).param("seat", seat)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT capacity = 5 AND sold = 2 AND held = 1
                        FROM showtime_zone_stock WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("showtime", showtime.id()).param("zone", showtime.event().standingZoneId())
                .query(Boolean.class).single()).isTrue();

        catalog.setSeat(showtime.id(), seat, "AVAILABLE", null, null);

        assertThat(jdbc.sql("""
                        SELECT status = 'AVAILABLE' AND order_id IS NULL AND held_until IS NULL
                        FROM showtime_seat WHERE showtime_id = :showtime AND seat_id = :seat
                        """)
                .param("showtime", showtime.id()).param("seat", seat).query(Boolean.class).single()).isTrue();
    }
}
