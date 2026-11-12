package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import vingo.booking.internal.OrderReader;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;

class BookingSharedServicesTest extends BookingTestSupport {

    @Autowired
    private OrderReader reader;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void holdSeats_suggestsOnlyRemainingSeat_whenFewerSeatsThanRequestedAreAvailable() {
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();
        UUID otherOrder = catalog.order(catalog.buyer(), showtime.id(), "PENDING", catalog.now().plusMinutes(10));
        catalog.setSeat(showtime.id(), event.seat(1), "HELD", otherOrder, catalog.now().plusMinutes(10));
        for (int number = 3; number <= 10; number++) {
            catalog.setSeat(showtime.id(), event.seat(number), "BLOCKED", null, null);
        }
        UUID buyer = catalog.buyer();

        String json = problem(hold(buyerToken(buyer), showtime.id(), seats(event.seat(1), event.seat(2))),
                409, "SEAT_TAKEN");

        assertThat(strings(json, "$.taken_seat_ids")).containsExactly(event.seat(1).toString());
        assertThat(strings(json, "$.suggested_seat_ids")).containsExactly(event.seat(2).toString());
        assertAvailable(showtime, event.seat(2));
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_suggestsReleasedRequestedSeatOnlyAfterRollback_andDoesNotKeepIdempotencyKey() {
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();
        String otherToken = buyerToken(catalog.buyer());
        UUID otherOrder = orderId(holdOk(otherToken, showtime.id(), seats(event.seat(1))));
        var takenBefore = seatState(showtime.id(), event.seat(1));
        UUID buyer = catalog.buyer();
        String token = buyerToken(buyer);
        String key = UUID.randomUUID().toString();
        String request = seats(event.seat(1), event.seat(2));

        String json = problem(hold(token, showtime.id(), key, request), 409, "SEAT_TAKEN");

        var suggested = strings(json, "$.suggested_seat_ids");
        assertThat(suggested).hasSize(2).doesNotHaveDuplicates()
                .contains(event.seat(2).toString()).doesNotContain(event.seat(1).toString());
        for (String seat : suggested) {
            assertAvailable(showtime, UUID.fromString(seat));
        }
        assertThat(seatState(showtime.id(), event.seat(1))).isEqualTo(takenBefore);
        assertThat(orderCount(buyer)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key WHERE user_id = :user AND key = :key")
                .param("user", buyer).param("key", key).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM realtime_event WHERE user_id = :user")
                .param("user", buyer).query(Integer.class).single()).isZero();

        body(cancel(otherToken, otherOrder), 200);
        UUID order = orderId(holdOk(token, showtime.id(), key, request));
        assertThat(orderId(holdOk(token, showtime.id(), key, request))).isEqualTo(order);
        assertThat(orderCount(buyer)).isEqualTo(1);
    }

    @Test
    void holdSeats_countsExpandedCoupleSeats_whenRequestingSuggestions() {
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();
        UUID otherOrder = catalog.order(catalog.buyer(), showtime.id(), "PENDING", catalog.now().plusMinutes(10));
        catalog.setSeat(showtime.id(), event.seat(10), "HELD", otherOrder, catalog.now().plusMinutes(10));

        String json = problem(hold(buyerToken(catalog.buyer()), showtime.id(), seats(event.seat(9))),
                409, "SEAT_TAKEN");

        assertThat(strings(json, "$.taken_seat_ids")).containsExactly(event.seat(10).toString());
        var suggested = strings(json, "$.suggested_seat_ids");
        assertThat(suggested).hasSize(2).doesNotHaveDuplicates().doesNotContain(event.seat(10).toString());
        for (String seat : suggested) {
            assertAvailable(showtime, UUID.fromString(seat));
        }
        assertAvailable(showtime, event.seat(9));
    }

    @Test
    void holdSeats_suggestsRequestedQuantityInEachSeatedZone() {
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();
        UUID secondZone = jdbc.sql("""
                        INSERT INTO zone (seat_layout_id, name, kind, capacity)
                        VALUES (:layout, 'B', 'SEATED', 4) RETURNING id
                        """)
                .param("layout", event.layoutId()).query(UUID.class).single();
        jdbc.sql("INSERT INTO zone_price (event_id, zone_id, price_tier_id) VALUES (:event, :zone, :tier)")
                .param("event", event.id()).param("zone", secondZone).param("tier", event.vipPriceTierId()).update();
        for (int number = 5; number <= 8; number++) {
            jdbc.sql("UPDATE seat SET zone_id = :zone WHERE id = :seat")
                    .param("zone", secondZone).param("seat", event.seat(number)).update();
        }
        UUID otherOrder = catalog.order(catalog.buyer(), showtime.id(), "PENDING", catalog.now().plusMinutes(10));
        for (int number : new int[]{1, 5}) {
            catalog.setSeat(showtime.id(), event.seat(number), "HELD", otherOrder, catalog.now().plusMinutes(10));
        }

        String json = problem(hold(buyerToken(catalog.buyer()), showtime.id(),
                seats(event.seat(1), event.seat(2), event.seat(5), event.seat(6))), 409, "SEAT_TAKEN");

        assertThat(strings(json, "$.taken_seat_ids"))
                .containsExactlyInAnyOrder(event.seat(1).toString(), event.seat(5).toString());
        var suggested = strings(json, "$.suggested_seat_ids");
        assertThat(suggested).containsExactlyInAnyOrder(event.seat(2).toString(), event.seat(3).toString(),
                event.seat(6).toString(), event.seat(7).toString());
        for (String seat : suggested) {
            assertAvailable(showtime, UUID.fromString(seat));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "SCHEDULED, -1, SCHEDULED, 409",
            "SCHEDULED, 0, ON_SALE, 201",
            "SCHEDULED, 168, SALES_CLOSED, 409",
            "SCHEDULED, 170, FINISHED, 409",
            "DRAFT, 0, DRAFT, 404",
            "CANCELLED, 0, CANCELLED, 409"
    })
    void booking_usesCatalogStatusAtClockBoundaries(String storedStatus, long hours, String expectedStatus,
                                                   int holdStatus) {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        var event = catalog.event();
        var showtime = catalog.showtime(event, new Window(storedStatus, Duration.ZERO, Duration.ofDays(7),
                Duration.ofDays(7), Duration.ofDays(7).plusHours(2)));
        UUID order = catalog.order(catalog.buyer(), showtime.id(), "PENDING", catalog.now().plusMinutes(10));
        clock.advance(Duration.ofHours(hours));

        assertThat(reader.readOrder(order, catalog.now()).getShowtime().getStatus().name())
                .isEqualTo(expectedStatus);
        var response = hold(buyerToken(catalog.buyer()), showtime.id(), seats(event.seat(1)));
        if (holdStatus == 201) {
            assertThat(text(body(response, 201), "$.showtime.status")).isEqualTo(expectedStatus);
        } else {
            problem(response, holdStatus, holdStatus == 404 ? "NOT_FOUND" : "SHOWTIME_NOT_ON_SALE");
        }
    }

    @Test
    void holdSeats_requiresPublishedEvent_evenWhenShowtimeIsOnSale() {
        var event = catalog.event(EventSpec.defaults().withStatus("DRAFT"));
        var showtime = catalog.showtime(event, Window.onSale());

        problem(hold(buyerToken(catalog.buyer()), showtime.id(), seats(event.seat(1))),
                409, "SHOWTIME_NOT_ON_SALE");
    }
}
