package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** GET /events/{id} và GET /events/{id}/showtimes trên Postgres thật, giờ điều khiển qua {@code MutableClock}. */
class EventDetailTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ------------------------------------------------------------------ getEvent

    @Test
    void getEvent_returnsDetailWithVenuePriceTiersAndShowtimes() {
        var created = fixtures.event()
                .title("Sơn Tùng M-TP Sky Tour")
                .description("Đêm nhạc lớn")
                .category("CONCERT")
                .posterUrl("https://cdn.vingo.test/poster.jpg")
                .city("HN")
                .tier("VIP", 2_000_000)
                .tier("Thường", 500_000)
                // Suất đã qua, ở sân cũ: không được chọn làm venue.
                .showtime(s -> s.venue("Sân cũ").startsAt(NOW.minus(Duration.ofDays(5))).saleWindow(
                        NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(5))).seated(2, "VIP"))
                // Hai suất sắp tới, suất sớm hơn ở "Sân gần".
                .showtime(s -> s.venue("Sân xa").startsAt(NOW.plus(Duration.ofDays(20))).onSale().seated(3, "Thường"))
                .showtime(s -> s.venue("Sân gần").startsAt(NOW.plus(Duration.ofDays(10))).onSale().seated(3, "VIP"))
                .create();

        JsonNode body = get("/api/v1/events/" + created.id());

        assertThat(body.path("id").asString()).isEqualTo(created.id().toString());
        assertThat(body.path("title").asString()).isEqualTo("Sơn Tùng M-TP Sky Tour");
        assertThat(body.path("description").asString()).isEqualTo("Đêm nhạc lớn");
        assertThat(body.path("category").asString()).isEqualTo("CONCERT");
        assertThat(body.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(body.path("poster_mobile_url").asString()).isEqualTo("https://cdn.vingo.test/poster.jpg");
        assertThat(body.path("max_tickets_per_order").asInt()).isEqualTo(10);
        assertThat(body.path("venue").path("name").asString()).isEqualTo("Sân gần");
        assertThat(body.path("venue").path("city").asString()).isEqualTo("HN");
        assertThat(body.path("venue").path("city_name").asString()).isEqualTo("Hà Nội");
        assertThat(body.path("price_tiers")).hasSize(2);
        assertThat(body.path("price_tiers").get(0).path("name").asString()).isEqualTo("Thường");
        assertThat(body.path("price_tiers").get(0).path("price").asLong()).isEqualTo(500_000);
        assertThat(body.path("price_tiers").get(1).path("price").asLong()).isEqualTo(2_000_000);
        assertThat(body.path("showtimes")).hasSize(3);
        // Sắp theo starts_at tăng dần.
        assertThat(body.path("showtimes").get(0).path("id").asString())
                .isEqualTo(created.showtimeIds().get(0).toString());
        assertThat(body.path("showtimes").get(1).path("id").asString())
                .isEqualTo(created.showtimeIds().get(2).toString());
        assertThat(body.path("showtimes").get(2).path("id").asString())
                .isEqualTo(created.showtimeIds().get(1).toString());
    }

    @Test
    void getEvent_usesVenueOfFirstShowtime_whenNoUpcomingShowtime() {
        UUID id = fixtures.event()
                .showtime(s -> s.venue("Sân A").startsAt(NOW.minus(Duration.ofDays(9))).saleWindow(
                        NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(9))).seated(1))
                .showtime(s -> s.venue("Sân B").startsAt(NOW.minus(Duration.ofDays(3))).saleWindow(
                        NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(3))).seated(1))
                .create().id();

        assertThat(get("/api/v1/events/" + id).path("venue").path("name").asString()).isEqualTo("Sân A");
    }

    @Test
    void getEvent_returns404_whenEventDraft() {
        UUID id = fixtures.event().draft().create().id();

        assertNotFound("/api/v1/events/" + id);
    }

    @Test
    void getEvent_returns404_whenEventEndedOrCancelled() {
        UUID ended = fixtures.event().status("ENDED").create().id();
        UUID cancelled = fixtures.event().status("CANCELLED").create().id();

        assertNotFound("/api/v1/events/" + ended);
        assertNotFound("/api/v1/events/" + cancelled);
    }

    @Test
    void getEvent_returns404_whenEventMissing() {
        assertNotFound("/api/v1/events/" + UUID.randomUUID());
    }

    // ------------------------------------------------------------------ listEventShowtimes

    @Test
    void listEventShowtimes_computesStatus_atEachBoundary() {
        Instant opens = NOW.plus(Duration.ofDays(1));
        Instant closes = NOW.plus(Duration.ofDays(2));
        Instant starts = closes;
        UUID id = fixtures.event()
                .showtime(s -> s.startsAt(starts).length(Duration.ofDays(1)).saleWindow(opens, closes).seated(5))
                .create().id();
        Instant ends = starts.plus(Duration.ofDays(1));

        assertThat(statusAt(id, opens.minusSeconds(1))).isEqualTo("SCHEDULED");
        assertThat(statusAt(id, opens)).isEqualTo("ON_SALE");
        assertThat(statusAt(id, closes.minusSeconds(1))).isEqualTo("ON_SALE");
        assertThat(statusAt(id, closes)).isEqualTo("SALES_CLOSED");
        assertThat(statusAt(id, ends.minusSeconds(1))).isEqualTo("SALES_CLOSED");
        assertThat(statusAt(id, ends)).isEqualTo("FINISHED");
    }

    @Test
    void listEventShowtimes_excludesDraftShowtimes_includesCancelled() {
        var created = fixtures.event()
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(3))).onSale().seated(1).draft())
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(4))).onSale().seated(1).cancelled())
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(5))).onSale().seated(1))
                .create();

        JsonNode data = get("/api/v1/events/" + created.id() + "/showtimes").path("data");

        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("id").asString()).isEqualTo(created.showtimeIds().get(1).toString());
        assertThat(data.get(0).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(data.get(1).path("id").asString()).isEqualTo(created.showtimeIds().get(2).toString());
        assertThat(data.get(1).path("status").asString()).isEqualTo("ON_SALE");
        // Chi tiết sự kiện cũng không lộ suất DRAFT.
        assertThat(get("/api/v1/events/" + created.id()).path("showtimes")).hasSize(2);
    }

    @Test
    void listEventShowtimes_marksSoldOut_whenNoSeatsLeft() {
        var created = fixtures.event()
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(3))).onSale().seated(2).sold(2))
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(4))).onSale().seated(2).standing(10).sold(2))
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(5))).onSale().seated(2).standing(10).sold(12))
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(6))).onSale().seated(2).sold(1))
                .create();
        String path = "/api/v1/events/" + created.id() + "/showtimes";

        JsonNode data = get(path).path("data");

        assertThat(data.get(0).path("sold_out").asBoolean()).isTrue();   // hết ghế ngồi
        assertThat(data.get(1).path("sold_out").asBoolean()).isFalse();  // khu đứng còn chỗ
        assertThat(data.get(2).path("sold_out").asBoolean()).isTrue();   // hết cả ghế lẫn khu đứng
        assertThat(data.get(3).path("sold_out").asBoolean()).isFalse();  // còn 1 ghế

        // Ghế HELD chưa quá hạn vẫn bị giữ; quá hạn thì tính là trống (Implicit Status).
        UUID showtime = created.showtimeIds().get(0);
        jdbc.sql("""
                UPDATE showtime_seat SET status = 'HELD', held_until = :until
                WHERE showtime_id = :s AND seat_id = (SELECT seat_id FROM showtime_seat WHERE showtime_id = :s LIMIT 1)
                """).param("s", showtime).param("until", NOW.plus(Duration.ofMinutes(10)).atOffset(java.time.ZoneOffset.UTC))
                .update();
        assertThat(get(path).path("data").get(0).path("sold_out").asBoolean()).isTrue();

        clock.set(NOW.plus(Duration.ofMinutes(11)));
        assertThat(get(path).path("data").get(0).path("sold_out").asBoolean()).isFalse();
    }

    @Test
    void listEventShowtimes_returns404_whenEventMissing() {
        assertNotFound("/api/v1/events/" + UUID.randomUUID() + "/showtimes");
    }

    @Test
    void listEventShowtimes_returns404_whenEventDraft() {
        UUID id = fixtures.event().draft().create().id();

        assertNotFound("/api/v1/events/" + id + "/showtimes");
    }

    // ------------------------------------------------------------------ Helper

    private String statusAt(UUID eventId, Instant at) {
        clock.set(at);
        return get("/api/v1/events/" + eventId + "/showtimes").path("data").get(0).path("status").asString();
    }

    private JsonNode get(String path) {
        String body = client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return JSON.readTree(body);
    }

    private void assertNotFound(String path) {
        String body = client.get().uri(path).exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JSON.readTree(body).path("code").asString()).isEqualTo("NOT_FOUND");
    }
}
