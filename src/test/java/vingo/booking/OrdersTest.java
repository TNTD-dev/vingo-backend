package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static vingo.support.CatalogFixture.VIP_PRICE;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;

class OrdersTest extends BookingTestSupport {

    private Showtime showtime;
    private UUID buyer;
    private String token;

    @BeforeEach
    void createBuyerAndShowtime() {
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        token = buyerToken(buyer);
    }

    @Test
    void getOrder_returns200WithSeatDetails_whenBuyerOwnsOrder() {
        UUID id = seatedHold();

        String json = body(get(token, "/api/v1/orders/{id}", id), 200);

        assertThat(orderId(json)).isEqualTo(id);
        assertThat(text(json, "$.status")).isEqualTo("PENDING");
        assertThat(strings(json, "$.items[*].seat_id")).containsExactly(showtime.event().seat(1).toString());
        assertThat(text(json, "$.items[0].row")).isEqualTo("A");
        assertThat(number(json, "$.items[0].number")).isEqualTo(1);
        assertThat(text(json, "$.items[0].zone")).isEqualTo("A");
    }

    @Test
    void getOrder_returns404_whenOrderBelongsToAnotherBuyer() {
        UUID id = seatedHold();
        problem(get(buyerToken(catalog.buyer()), "/api/v1/orders/{id}", id), 404, "NOT_FOUND");
    }

    @Test
    void getOrder_returns404_whenAdminReadsAnotherBuyersOrder() {
        UUID id = seatedHold();
        String adminToken = tokenFor(Role.ADMIN);

        problem(get(adminToken, "/api/v1/orders/{id}", id), 404, "NOT_FOUND");
    }

    @Test
    void getOrder_returns404_whenOrderDoesNotExist() {
        problem(get(token, "/api/v1/orders/{id}", UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    void getOrder_returns401_whenTokenMissing() {
        UUID id = seatedHold();
        problem(get(null, "/api/v1/orders/{id}", id), 401, "UNAUTHORIZED");
    }

    @Test
    void listMyOrders_returnsOnlyOwnOrdersNewestFirst() {
        UUID oldest = seedOrder(buyer, "PAID", 1, 3);
        UUID middle = seedOrder(buyer, "EXPIRED", 2, 2);
        UUID newest = seedOrder(buyer, "CANCELLED", 3, 1);
        seedOrder(catalog.buyer(), "PAID", 4, 0);

        String json = body(get(token, "/api/v1/orders"), 200);

        assertThat(strings(json, "$.data[*].id"))
                .containsExactly(newest.toString(), middle.toString(), oldest.toString());
    }

    @Test
    void listMyOrders_filtersPaidOrdersAndExcludesOtherBuyersPaidOrders() {
        UUID paid = seedOrder(buyer, "PAID", 1, 2);
        seedOrder(buyer, "CANCELLED", 2, 1);
        seedOrder(catalog.buyer(), "PAID", 3, 0);

        String json = body(get(token, "/api/v1/orders?status=PAID"), 200);

        assertThat(strings(json, "$.data[*].id")).containsExactly(paid.toString());
        assertThat(strings(json, "$.data[*].status")).containsExactly("PAID");
    }

    @Test
    void listMyOrders_paginatesFiveOrdersInThreePagesWithoutDuplicatesOrOmissions() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.addFirst(seedOrder(buyer, "CANCELLED", i + 1, 5 - i).toString());
        }
        seedOrder(catalog.buyer(), "PAID", 6, 0);
        List<String> actual = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            String json = page == 0
                    ? body(get(token, "/api/v1/orders?limit=2"), 200)
                    : body(get(token, "/api/v1/orders?limit=2&cursor={cursor}", cursor), 200);
            var ids = strings(json, "$.data[*].id");
            assertThat(ids).hasSize(page < 2 ? 2 : 1);
            actual.addAll(ids);
            cursor = text(json, "$.next_cursor");
            if (page < 2) {
                assertThat(cursor).isNotBlank();
                assertThat(cursors).doesNotContain(cursor);
                cursors.add(cursor);
            } else {
                assertThat(cursor).isNull();
            }
        }
        assertThat(actual).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
    }

    @Test
    void listMyOrders_returns400_whenLimitExceedsOneHundred() {
        problem(get(token, "/api/v1/orders?limit=101"), 400, "VALIDATION_ERROR");
    }

    @Test
    void listMyOrders_returns401_whenTokenMissing() {
        problem(get(null, "/api/v1/orders"), 401, "UNAUTHORIZED");
    }

    @Test
    void cancelOrder_returns200AndReleasesSeatAndRecordsBuyerAsActor() {
        UUID id = seatedHold();

        String json = body(cancel(token, id), 200);

        assertThat(orderId(json)).isEqualTo(id);
        assertThat(text(json, "$.status")).isEqualTo("CANCELLED");
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(historyCount(id, "PENDING", "CANCELLED")).isEqualTo(1);
        assertHistoryActor(id, "CANCELLED", buyer);
    }

    @Test
    void cancelOrder_releasesStandingHeldQuantity() {
        UUID id = orderId(holdOk(token, showtime.id(), standing(showtime.event().standingZoneId(), 3)));
        assertThat(held(showtime)).isEqualTo(3);

        String json = body(cancel(token, id), 200);

        assertThat(text(json, "$.status")).isEqualTo("CANCELLED");
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(held(showtime)).isZero();
        assertThat(historyCount(id, "PENDING", "CANCELLED")).isEqualTo(1);
        assertHistoryActor(id, "CANCELLED", buyer);
    }

    @Test
    void cancelOrder_allowsImmediateNewHoldOnSameShowtimeAndSeat() {
        UUID old = seatedHold();
        body(cancel(token, old), 200);

        UUID fresh = seatedHold();

        assertThat(fresh).isNotEqualTo(old);
        assertThat(orderStatus(old)).isEqualTo("CANCELLED");
        assertThat(orderStatus(fresh)).isEqualTo("PENDING");
        assertThat(seatState(showtime.id(), showtime.event().seat(1)).orderId()).isEqualTo(fresh);
        assertThat(orderCount(buyer)).isEqualTo(2);
    }

    @Test
    void cancelOrder_returns409_whenOrderPaid() {
        UUID id = seedOrder(buyer, "PAID", 1, 0);
        var before = seatState(showtime.id(), showtime.event().seat(1));

        problem(cancel(token, id), 409, "ORDER_STATE_INVALID");

        assertThat(orderStatus(id)).isEqualTo("PAID");
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(before);
        assertThat(historyCount(id, "PENDING", "CANCELLED")).isZero();
    }

    @Test
    void cancelOrder_returns409_whenOrderExpired() {
        UUID id = seedOrder(buyer, "EXPIRED", 1, 0);

        problem(cancel(token, id), 409, "ORDER_STATE_INVALID");

        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(historyCount(id, "PENDING", "CANCELLED")).isZero();
    }

    @Test
    void cancelOrder_returns409_whenCancelledTwice() {
        UUID id = seatedHold();
        body(cancel(token, id), 200);

        problem(cancel(token, id), 409, "ORDER_STATE_INVALID");

        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(historyCount(id, "PENDING", "CANCELLED")).isEqualTo(1);
        assertAvailable(showtime, showtime.event().seat(1));
    }

    @Test
    void cancelOrder_returns404AndChangesNothing_whenOrderBelongsToAnotherBuyer() {
        UUID id = seatedHold();
        var before = seatState(showtime.id(), showtime.event().seat(1));

        problem(cancel(buyerToken(catalog.buyer()), id), 404, "NOT_FOUND");

        assertThat(orderStatus(id)).isEqualTo("PENDING");
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(before);
        assertThat(historyCount(id, "PENDING", "CANCELLED")).isZero();
        assertThat(historyCount(id, null, "PENDING")).isEqualTo(1);
    }

    @Test
    void cancelOrder_returns404_whenOrderDoesNotExist() {
        problem(cancel(token, UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    void cancelOrder_returns401AndChangesNothing_whenTokenMissing() {
        UUID id = seatedHold();
        var before = seatState(showtime.id(), showtime.event().seat(1));

        problem(cancel(null, id), 401, "UNAUTHORIZED");

        assertThat(orderStatus(id)).isEqualTo("PENDING");
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(before);
    }

    private UUID seatedHold() {
        return orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1))));
    }

    private UUID seedOrder(UUID userId, String status, int seatNumber, int minutesAgo) {
        UUID id = catalog.order(userId, showtime.id(), status, catalog.now().minusMinutes(1));
        jdbc.sql("""
                        UPDATE orders SET total_amount = :price, created_at = :created WHERE id = :id
                        """)
                .param("price", VIP_PRICE).param("created", catalog.now().minusMinutes(minutesAgo))
                .param("id", id).update();
        jdbc.sql("""
                        INSERT INTO order_item (order_id, zone_id, seat_id, price_tier_id, quantity, unit_price)
                        VALUES (:order, :zone, :seat, :tier, 1, :price)
                        """)
                .param("order", id).param("zone", showtime.event().seatedZoneId())
                .param("seat", showtime.event().seat(seatNumber)).param("tier", showtime.event().vipPriceTierId())
                .param("price", VIP_PRICE).update();
        if (status.equals("PAID")) {
            catalog.setSeat(showtime.id(), showtime.event().seat(seatNumber), "SOLD", id, null);
        }
        return id;
    }
}
