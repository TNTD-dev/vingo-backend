package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;
import vingo.support.IntegrationTest;

abstract class BookingTestSupport extends IntegrationTest {

    protected String buyerToken(UUID userId) {
        return tokenFor(userId, Role.BUYER);
    }

    protected ResponseSpec hold(String token, UUID showtimeId, String idempotencyKey, String json) {
        return client.post().uri("/api/v1/showtimes/{id}/holds", showtimeId)
                .headers(headers -> {
                    if (token != null) headers.setBearerAuth(token);
                    if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
                })
                .contentType(MediaType.APPLICATION_JSON).body(json).exchange();
    }

    protected ResponseSpec hold(String token, UUID showtimeId, String json) {
        return hold(token, showtimeId, UUID.randomUUID().toString(), json);
    }

    protected String holdOk(String token, UUID showtimeId, String json) {
        return body(hold(token, showtimeId, json), 201);
    }

    protected String holdOk(String token, UUID showtimeId, String key, String json) {
        return body(hold(token, showtimeId, key, json), 201);
    }

    protected ResponseSpec get(String token, String uri, Object... vars) {
        return client.get().uri(uri, vars)
                .headers(headers -> { if (token != null) headers.setBearerAuth(token); }).exchange();
    }

    protected ResponseSpec cancel(String token, UUID orderId) {
        return client.post().uri("/api/v1/orders/{id}/cancel", orderId)
                .headers(headers -> { if (token != null) headers.setBearerAuth(token); }).exchange();
    }

    protected String seats(UUID... seatIds) {
        return "{\"seat_ids\":[" + Arrays.stream(seatIds).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
    }

    protected String standing(UUID zoneId, int quantity) {
        return "{\"zone_id\":\"%s\",\"quantity\":%d}".formatted(zoneId, quantity);
    }

    protected String body(ResponseSpec response, int status) {
        return response.expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
    }

    protected String problem(ResponseSpec response, int status, String code) {
        String json = body(response.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON), status);
        assertThat(text(json, "$.code")).isEqualTo(code);
        assertThat(number(json, "$.status")).isEqualTo(status);
        assertThat(text(json, "$.title")).isNotBlank();
        return json;
    }

    protected String text(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected List<String> strings(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    protected UUID orderId(String json) {
        return UUID.fromString(text(json, "$.id"));
    }

    protected SeatState seatState(UUID showtimeId, UUID seatId) {
        return jdbc.sql("""
                        SELECT status, order_id, held_until FROM showtime_seat
                        WHERE showtime_id = :showtime AND seat_id = :seat
                        """)
                .param("showtime", showtimeId).param("seat", seatId)
                .query((rs, row) -> new SeatState(rs.getString("status"), rs.getObject("order_id", UUID.class),
                        rs.getObject("held_until", OffsetDateTime.class))).single();
    }

    protected String orderStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    protected int held(Showtime showtime) {
        return jdbc.sql("SELECT held FROM showtime_zone_stock WHERE showtime_id = :showtime AND zone_id = :zone")
                .param("showtime", showtime.id()).param("zone", showtime.event().standingZoneId())
                .query(Integer.class).single();
    }

    protected int orderCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE user_id = :user")
                .param("user", userId).query(Integer.class).single();
    }

    protected int pendingCount(UUID showtimeId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE showtime_id = :id AND status = 'PENDING'")
                .param("id", showtimeId).query(Integer.class).single();
    }

    protected void assertAvailable(Showtime showtime, UUID... seats) {
        for (UUID seat : seats) {
            assertThat(seatState(showtime.id(), seat)).isEqualTo(new SeatState("AVAILABLE", null, null));
        }
    }

    protected int historyCount(UUID orderId, String from, String to) {
        return jdbc.sql("""
                        SELECT count(*) FROM order_status_history WHERE order_id = :id
                        AND from_status IS NOT DISTINCT FROM CAST(:from AS text) AND to_status = :to
                        """)
                .param("id", orderId).param("from", from, java.sql.Types.VARCHAR).param("to", to)
                .query(Integer.class).single();
    }

    protected void assertHistoryActor(UUID orderId, String to, UUID actorId) {
        assertThat(jdbc.sql("""
                        SELECT actor_id FROM order_status_history WHERE order_id = :id AND to_status = :to
                        """)
                .param("id", orderId).param("to", to).query().singleRow()).containsEntry("actor_id", actorId);
    }

    protected int eventCount(UUID userId, UUID orderId, String type) {
        return jdbc.sql("""
                        SELECT count(*) FROM realtime_event
                        WHERE user_id = :user AND type = :type AND payload->>'order_id' = :order
                        """)
                .param("user", userId).param("type", type).param("order", orderId.toString())
                .query(Integer.class).single();
    }

    protected void makeOverdue(UUID orderId) {
        var past = catalog.now().minusMinutes(1);
        jdbc.sql("UPDATE orders SET expires_at = :past WHERE id = :id")
                .param("past", past).param("id", orderId).update();
        jdbc.sql("UPDATE showtime_seat SET held_until = :past WHERE order_id = :id AND status = 'HELD'")
                .param("past", past).param("id", orderId).update();
    }

    protected void assertNear(OffsetDateTime actual, OffsetDateTime expected) {
        assertThat(Duration.between(expected, actual).abs()).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    protected <T> List<T> race(List<Callable<T>> actions) throws Exception {
        var ready = new CountDownLatch(actions.size());
        var start = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (var action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) throw new AssertionError("Start latch timed out");
                    return action.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).as("All callers ready").isTrue();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("All callers stopped").isTrue();
        }
    }

    protected record SeatState(String status, UUID orderId, OffsetDateTime heldUntil) {}
}
