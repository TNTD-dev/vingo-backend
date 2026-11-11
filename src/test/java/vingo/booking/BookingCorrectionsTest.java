package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vingo.booking.internal.OrderReader;
import vingo.support.CatalogFixture.Window;

@ExtendWith(OutputCaptureExtension.class)
class BookingCorrectionsTest extends BookingTestSupport {

    @Autowired
    private OrderReader reader;

    @MockitoSpyBean
    private JdbcClient queryClient;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @ParameterizedTest
    @ValueSource(longs = {24, 25})
    void holdSeats_createsNewOrderWithDifferentBody_whenKeyIsAtLeast24HoursOld(long hours) {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        String token = buyerToken(buyer);
        String key = UUID.randomUUID().toString();
        UUID first = orderId(holdOk(token, showtime.id(), key, seats(showtime.event().seat(1))));
        assertThat(jdbc.sql("SELECT created_at FROM idempotency_key WHERE user_id = :user AND key = :key")
                .param("user", buyer).param("key", key).query(OffsetDateTime.class).single())
                .isEqualTo(catalog.now());

        clock.advance(Duration.ofHours(hours));
        String result = holdOk(token, showtime.id(), key, seats(showtime.event().seat(2)));

        assertThat(orderId(result)).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("EXPIRED");
        assertThat(strings(result, "$.items[*].seat_id")).containsExactly(showtime.event().seat(2).toString());
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(jdbc.sql("SELECT created_at FROM idempotency_key WHERE user_id = :user AND key = :key")
                .param("user", buyer).param("key", key).query(OffsetDateTime.class).single())
                .isEqualTo(catalog.now());
    }

    @Test
    void holdSeats_rejectsDifferentBody_whenKeyIsJustUnder24HoursOld() {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        String token = buyerToken(catalog.buyer());
        String key = UUID.randomUUID().toString();
        UUID first = orderId(holdOk(token, showtime.id(), key, seats(showtime.event().seat(1))));
        clock.advance(Duration.ofHours(24).minusSeconds(1));

        problem(hold(token, showtime.id(), key, seats(showtime.event().seat(2))), 409, "IDEMPOTENCY_CONFLICT");

        assertThat(orderId(holdOk(token, showtime.id(), key, seats(showtime.event().seat(1)))))
                .isEqualTo(first);
    }

    @Test
    void readOrders_readsTwoQueriesAndPreservesPageOrderAndEachOrdersItems() {
        var firstShow = catalog.onSaleShowtime();
        var secondShow = catalog.onSaleShowtime();
        String token = buyerToken(catalog.buyer());
        UUID first = orderId(holdOk(token, firstShow.id(), seats(firstShow.event().seat(2),
                firstShow.event().seat(1))));
        UUID second = orderId(holdOk(token, secondShow.id(), standing(secondShow.event().standingZoneId(), 2)));
        clearInvocations(queryClient);

        var orders = reader.readOrders(List.of(second, first), catalog.now());

        verify(queryClient, times(2)).sql(anyString());
        assertThat(orders).extracting(order -> order.getId()).containsExactly(second, first);
        assertThat(orders.getFirst().getItems()).singleElement().satisfies(item -> {
            assertThat(item.getZone()).isEqualTo("GA");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getSeatId()).isNull();
        });
        assertThat(orders.getLast().getItems()).extracting(item -> item.getSeatId())
                .containsExactly(firstShow.event().seat(1), firstShow.event().seat(2));
        clearInvocations(queryClient);
        assertThat(reader.readOrder(first, catalog.now())).isEqualTo(orders.getLast());
        verify(queryClient, times(2)).sql(anyString());
        clearInvocations(queryClient);

        String page = body(get(token, "/api/v1/orders"), 200);

        verify(queryClient, times(3)).sql(anyString());
        assertThat(strings(page, "$.data[*].id")).containsExactly(second.toString(), first.toString());
    }

    @Test
    void getOrder_includesSearchSummaryUsingOnlyAssignedPricesAndValidFutureShowtimes() {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();
        for (String status : List.of("DRAFT", "CANCELLED")) {
            catalog.showtime(event, new Window(status, Duration.ofDays(-1), Duration.ofDays(1),
                    Duration.ofDays(1), Duration.ofDays(1).plusHours(2)));
        }
        catalog.showtime(event, Window.finished());
        jdbc.sql("INSERT INTO price_tier (event_id, name, price) VALUES (:event, 'Unassigned', 1)")
                .param("event", event.id()).update();
        String token = buyerToken(catalog.buyer());
        UUID id = orderId(holdOk(token, showtime.id(), seats(event.seat(1))));

        String json = body(get(token, "/api/v1/orders/{id}", id), 200);

        assertThat(number(json, "$.event.price_from")).isEqualTo(200_000);
        assertThat(OffsetDateTime.parse(text(json, "$.event.first_starts_at")))
                .isEqualTo(catalog.now().plusDays(7));
        assertThat(JsonPath.<Boolean>read(json, "$.event.on_sale")).isTrue();

        clock.advance(Duration.ofDays(7));
        json = body(get(token, "/api/v1/orders/{id}", id), 200);
        assertThat(JsonPath.<Boolean>read(json, "$.event.on_sale")).isFalse();
    }

    @Test
    void cancelOrder_logsErrorWithInventoryKeys_whenStandingHeldIsInconsistent(CapturedOutput output) {
        var showtime = catalog.onSaleShowtime();
        String token = buyerToken(catalog.buyer());
        UUID id = orderId(holdOk(token, showtime.id(), standing(showtime.event().standingZoneId(), 2)));
        catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 0, 1);

        body(cancel(token, id), 200);

        assertThat(held(showtime)).isEqualTo(1);
        assertThat(output.getAll().lines()
                .filter(line -> line.startsWith("{") && line.contains("held khu đứng lệch")).toList())
                .anySatisfy(line -> {
                    assertThat(text(line, "$.level")).isEqualTo("ERROR");
                    assertThat(text(line, "$.message")).isEqualTo("held khu đứng lệch, không trả held");
                    assertThat(text(line, "$.order_id")).isEqualTo(id.toString());
                    assertThat(text(line, "$.showtime_id")).isEqualTo(showtime.id().toString());
                });
    }
}
