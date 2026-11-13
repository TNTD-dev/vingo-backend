package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.catalog.CatalogChanged;
import vingo.catalog.VenueChanged;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** Cache Redis của search: đọc từ cache, xoá sau commit, status tính lại theo Clock, TTL availability. */
class SearchCacheTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private StringRedisTemplate redis;

    private CatalogFixtures fixtures;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
        tx = new TransactionTemplate(txManager);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void getEvent_servesFromCache_onSecondCall() {
        UUID eventId = fixtures.event().title("Tên cũ").create().id();
        assertThat(eventTitle(eventId)).isEqualTo("Tên cũ");

        renameEvent(eventId, "Tên mới");

        // Không có event xoá cache nên vẫn là dữ liệu cũ.
        assertThat(eventTitle(eventId)).isEqualTo("Tên cũ");
        assertThat(redis.keys("vingo:search:search-event:*")).hasSize(1);
    }

    @Test
    void getEvent_reflectsChange_afterCatalogChangedEvent() {
        UUID eventId = fixtures.event().title("Tên cũ").create().id();
        eventTitle(eventId);
        renameEvent(eventId, "Tên mới");

        tx.executeWithoutResult(status -> publisher.publishEvent(new CatalogChanged(eventId, Set.of())));

        assertThat(eventTitle(eventId)).isEqualTo("Tên mới");
    }

    @Test
    void getEvent_reflectsChange_afterVenueChangedEvent() {
        UUID eventId = fixtures.event().title("Tên cũ").create().id();
        eventTitle(eventId);
        get("/api/v1/events/" + eventId + "/showtimes");
        renameEvent(eventId, "Tên mới");

        tx.executeWithoutResult(status -> publisher.publishEvent(new VenueChanged(UUID.randomUUID())));

        assertThat(eventTitle(eventId)).isEqualTo("Tên mới");
    }

    @Test
    void getEvent_doesNotEvict_whenTransactionRollsBack() {
        UUID eventId = fixtures.event().title("Tên cũ").create().id();
        eventTitle(eventId);
        renameEvent(eventId, "Tên mới");

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new CatalogChanged(eventId, Set.of()));
            status.setRollbackOnly();
        });

        assertThat(eventTitle(eventId)).isEqualTo("Tên cũ");
    }

    @Test
    void getSeatmap_servesLayoutFromCache_butStateFromDb() {
        var created = fixtures.event().showtime(s -> s.onSale().seated(2)).create();
        UUID showtime = created.showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        UUID seat = fixtures.seatIds(zone).getFirst();
        JsonNode first = get("/api/v1/showtimes/" + showtime + "/seatmap");
        assertThat(first.path("zones").get(0).path("seats").get(0).path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(redis.keys("vingo:search:search-seatmap-layout:*")).hasSize(1);

        fixtures.sellSeat(showtime, seat);
        jdbc.sql("UPDATE seat SET x = 999 WHERE id = :id").param("id", seat).update();

        JsonNode second = get("/api/v1/showtimes/" + showtime + "/seatmap");
        JsonNode firstSeat = second.path("zones").get(0).path("seats").get(0);
        // Trạng thái ghế đọc DB mỗi lần; toạ độ là layout nên còn từ cache.
        assertThat(firstSeat.path("status").asString()).isEqualTo("SOLD");
        assertThat(firstSeat.path("x").asInt()).isNotEqualTo(999);

        tx.executeWithoutResult(status ->
                publisher.publishEvent(new CatalogChanged(created.id(), Set.of(showtime))));

        JsonNode third = get("/api/v1/showtimes/" + showtime + "/seatmap");
        assertThat(third.path("zones").get(0).path("seats").get(0).path("x").asInt()).isEqualTo(999);
    }

    @Test
    void getEvent_recomputesSoldOut_whenEventIsCached() {
        var created = fixtures.event().showtime(s -> s.onSale().seated(1)).create();
        UUID showtime = created.showtimeId();
        UUID seat = fixtures.seatIds(fixtures.zoneIds(showtime).getFirst()).getFirst();
        assertThat(get("/api/v1/events/" + created.id()).path("showtimes").get(0).path("sold_out").asBoolean())
                .isFalse();

        fixtures.sellSeat(showtime, seat);

        assertThat(get("/api/v1/events/" + created.id()).path("showtimes").get(0).path("sold_out").asBoolean())
                .isTrue();
    }

    @Test
    void listEventShowtimes_recomputesStatus_whenClockMoves_evenIfCached() {
        UUID eventId = fixtures.event()
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(20)))
                        .saleWindow(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(10))).seated(2))
                .create().id();
        String path = "/api/v1/events/" + eventId + "/showtimes";

        assertThat(get(path).path("data").get(0).path("status").asString()).isEqualTo("SCHEDULED");
        assertThat(redis.keys("vingo:search:search-showtimes:*")).hasSize(1);

        clock.set(NOW.plus(Duration.ofHours(2)));

        assertThat(get(path).path("data").get(0).path("status").asString()).isEqualTo("ON_SALE");
        clock.set(NOW.plus(Duration.ofDays(11)));
        assertThat(get(path).path("data").get(0).path("status").asString()).isEqualTo("SALES_CLOSED");
    }

    @Test
    void getAvailability_refreshesAfterTtl() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2)).create().showtimeId();
        String path = "/api/v1/showtimes/" + showtime + "/availability";
        assertThat(seatsLeft(path)).isEqualTo(2);

        fixtures.holdSeat(showtime, fixtures.seatIds(fixtures.zoneIds(showtime).getFirst()).getFirst(),
                NOW.plus(Duration.ofMinutes(10)));

        // Trong 3 giây đầu vẫn là số cũ, hết TTL thì đọc lại DB.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(seatsLeft(path)).isEqualTo(1));
    }

    @Test
    void getAvailability_doesNotCacheNotFound() {
        UUID showtime = UUID.randomUUID();
        client.get().uri("/api/v1/showtimes/" + showtime + "/availability").exchange().expectStatus().isNotFound();
        assertThat(redis.keys("vingo:search:search-availability:*")).isEmpty();
    }

    @Test
    void getEvent_returns200_whenRedisRejectsWrites() {
        UUID eventId = fixtures.event().title("Tên cũ").create().id();
        // Redis hết bộ nhớ: đọc được, ghi bị từ chối (OOM) nên bước put cache lỗi sau khi đã nạp xong từ DB.
        setRedisMaxMemory("1");
        try {
            assertThatThrownBy(() -> redis.opsForValue().set("probe", "x")).as("Redis từ chối ghi").isNotNull();
            assertThat(eventTitle(eventId)).isEqualTo("Tên cũ");
            assertThat(eventTitle(eventId)).isEqualTo("Tên cũ");
        } finally {
            setRedisMaxMemory("0");
        }
    }

    private void setRedisMaxMemory(String bytes) {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().setConfig("maxmemory", bytes);
            return null;
        });
    }

    private int seatsLeft(String path) {
        return get(path).path("zones").get(0).path("seats_left").asInt();
    }

    private String eventTitle(UUID eventId) {
        return get("/api/v1/events/" + eventId).path("title").asString();
    }

    private void renameEvent(UUID eventId, String title) {
        jdbc.sql("UPDATE event SET title = :t WHERE id = :id").param("t", title).param("id", eventId).update();
    }

    private JsonNode get(String path) {
        String body = client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return JSON.readTree(body);
    }
}
