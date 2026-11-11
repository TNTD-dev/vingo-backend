package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/**
 * GET /showtimes/{id}/seatmap, GET /showtimes/{id}/availability và {@code SearchService.suggestSeats} trên
 * Postgres thật, giờ điều khiển qua {@code MutableClock}.
 */
class SeatmapTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private SearchService searchService;

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

    // ------------------------------------------------------------------ getSeatmap

    @Test
    void getSeatmap_reportsExpiredHoldAsAvailable() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        List<UUID> seats = fixtures.seatIds(fixtures.zoneIds(showtime).getFirst());
        fixtures.holdSeat(showtime, seats.get(0), NOW.minusSeconds(60));

        JsonNode zone = get("/api/v1/showtimes/" + showtime + "/seatmap").path("zones").get(0);

        assertThat(statuses(zone)).containsExactly("AVAILABLE", "AVAILABLE", "AVAILABLE");
    }

    @Test
    void getSeatmap_reportsActiveHoldAsHeld() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        List<UUID> seats = fixtures.seatIds(fixtures.zoneIds(showtime).getFirst());
        fixtures.holdSeat(showtime, seats.get(0), NOW.plusSeconds(300));
        fixtures.sellSeat(showtime, seats.get(1));
        fixtures.blockSeat(showtime, seats.get(2));

        JsonNode zone = get("/api/v1/showtimes/" + showtime + "/seatmap").path("zones").get(0);

        assertThat(statuses(zone)).containsExactly("HELD", "SOLD", "BLOCKED");
    }

    @Test
    void getSeatmap_includesCouplePairAndStandingSeatsLeft() {
        var created = fixtures.event()
                .tier("VIP", 2_000_000).tier("Thường", 500_000)
                .showtime(s -> s.onSale().seatedRows(2, 2, "VIP").standing(100, "Thường"))
                .create();
        UUID showtime = created.showtimeId();
        List<UUID> zones = fixtures.zoneIds(showtime);
        List<UUID> seats = fixtures.seatIds(zones.get(0));
        fixtures.makeCouple(seats.get(2), seats.get(3));
        fixtures.setZoneStock(showtime, zones.get(1), 30, 10);

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/seatmap");

        assertThat(body.path("showtime_id").asString()).isEqualTo(showtime.toString());
        assertThat(body.path("status").asString()).isEqualTo("ON_SALE");
        JsonNode seated = body.path("zones").get(0);
        assertThat(seated.path("kind").asString()).isEqualTo("SEATED");
        assertThat(seated.path("tier").path("name").asString()).isEqualTo("VIP");
        assertThat(seated.path("tier").path("price").asLong()).isEqualTo(2_000_000);
        JsonNode list = seated.path("seats");
        assertThat(list).hasSize(4);
        assertThat(list.get(0).path("row").asString()).isEqualTo("A");
        assertThat(list.get(0).path("number").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("seat_type").asString()).isEqualTo("STANDARD");
        assertThat(list.get(0).path("seat_id").asString()).isEqualTo(seats.get(0).toString());
        assertThat(list.get(0).path("pair_seat_id").isMissingNode() || list.get(0).path("pair_seat_id").isNull())
                .isTrue();
        assertThat(list.get(2).path("row").asString()).isEqualTo("B");
        assertThat(list.get(2).path("seat_type").asString()).isEqualTo("COUPLE");
        // Cặp tra được từ cả hai chiều.
        assertThat(list.get(2).path("pair_seat_id").asString()).isEqualTo(seats.get(3).toString());
        assertThat(list.get(3).path("pair_seat_id").asString()).isEqualTo(seats.get(2).toString());
        JsonNode standing = body.path("zones").get(1);
        assertThat(standing.path("kind").asString()).isEqualTo("STANDING");
        assertThat(standing.path("seats_left").asInt()).isEqualTo(60);
        assertThat(standing.path("tier").path("name").asString()).isEqualTo("Thường");
        assertThat(standing.path("seats").size()).isZero();
    }

    @Test
    void getSeatmap_leavesTierEmpty_whenZoneHasNoPriceTier() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        jdbc.sql("DELETE FROM zone_price WHERE zone_id = :z").param("z", zone).update();

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/seatmap").path("zones").get(0);

        assertThat(body.path("tier").isMissingNode() || body.path("tier").isNull()).isTrue();
    }

    @Test
    void getSeatmap_returnsOnlyRequestedZone_whenZoneIdGiven() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2).seated(3)).create().showtimeId();
        List<UUID> zones = fixtures.zoneIds(showtime);

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/seatmap?zone_id=" + zones.get(1));

        assertThat(body.path("zones")).hasSize(1);
        assertThat(body.path("zones").get(0).path("zone_id").asString()).isEqualTo(zones.get(1).toString());
        assertThat(body.path("zones").get(0).path("seats")).hasSize(3);
    }

    @Test
    void getSeatmap_returns404_whenZoneNotInShowtimeLayout() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2)).create().showtimeId();
        UUID otherShowtime = fixtures.event().showtime(s -> s.onSale().seated(2)).create().showtimeId();
        UUID foreignZone = fixtures.zoneIds(otherShowtime).getFirst();

        assertNotFound("/api/v1/showtimes/" + showtime + "/seatmap?zone_id=" + foreignZone);
        assertNotFound("/api/v1/showtimes/" + showtime + "/seatmap?zone_id=" + UUID.randomUUID());
    }

    @Test
    void getSeatmap_returns404_whenShowtimeDraft() {
        UUID showtime = fixtures.event().showtime(s -> s.draft().seated(2)).create().showtimeId();

        assertNotFound("/api/v1/showtimes/" + showtime + "/seatmap");
    }

    @Test
    void getSeatmap_returns404_whenEventNotPublished() {
        UUID showtime = fixtures.event().draft().showtime(s -> s.onSale().seated(2)).create().showtimeId();

        assertNotFound("/api/v1/showtimes/" + showtime + "/seatmap");
    }

    @Test
    void getSeatmap_returns404_whenShowtimeMissing() {
        assertNotFound("/api/v1/showtimes/" + UUID.randomUUID() + "/seatmap");
    }

    @Test
    void getSeatmap_reportsBlocked_whenShowtimeSeatRowMissing() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).standing(50)).create().showtimeId();
        List<UUID> zones = fixtures.zoneIds(showtime);
        fixtures.deleteShowtimeSeat(showtime, fixtures.seatIds(zones.get(0)).get(1));
        fixtures.deleteZoneStock(showtime, zones.get(1));

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/seatmap");

        assertThat(statuses(body.path("zones").get(0))).containsExactly("AVAILABLE", "BLOCKED", "AVAILABLE");
        assertThat(body.path("zones").get(1).path("seats_left").asInt()).isZero();
    }

    @Test
    void getSeatmap_computesStatusFromClock() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(1)).create().showtimeId();
        clock.set(NOW.plus(Duration.ofDays(30)));

        assertThat(get("/api/v1/showtimes/" + showtime + "/seatmap").path("status").asString())
                .isEqualTo("FINISHED");
    }

    // ------------------------------------------------------------------ getAvailability

    @Test
    void getAvailability_countsSeatsLeftPerZone_withImplicitStatus() {
        var created = fixtures.event()
                .tier("VIP", 2_000_000).tier("Thường", 500_000)
                .showtime(s -> s.onSale().seated(5, "VIP").standing(100, "Thường"))
                .create();
        UUID showtime = created.showtimeId();
        List<UUID> zones = fixtures.zoneIds(showtime);
        List<UUID> seats = fixtures.seatIds(zones.get(0));
        fixtures.holdSeat(showtime, seats.get(0), NOW.minusSeconds(1));    // quá hạn: tính là trống
        fixtures.holdSeat(showtime, seats.get(1), NOW.plusSeconds(300));   // còn hạn: không trống
        fixtures.sellSeat(showtime, seats.get(2));
        fixtures.deleteShowtimeSeat(showtime, seats.get(3));               // thiếu dòng: không tính
        fixtures.setZoneStock(showtime, zones.get(1), 30, 10);

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/availability");

        assertThat(body.path("showtime_id").asString()).isEqualTo(showtime.toString());
        assertThat(body.path("status").asString()).isEqualTo("ON_SALE");
        assertThat(body.path("sold_out").asBoolean()).isFalse();
        assertThat(body.path("zones")).hasSize(2);
        assertThat(body.path("zones").get(0).path("zone_id").asString()).isEqualTo(zones.get(0).toString());
        assertThat(body.path("zones").get(0).path("seats_left").asInt()).isEqualTo(2);
        assertThat(body.path("zones").get(0).path("tier").path("name").asString()).isEqualTo("VIP");
        assertThat(body.path("zones").get(1).path("seats_left").asInt()).isEqualTo(60);
        assertThat(body.path("zones").get(1).path("tier").path("name").asString()).isEqualTo("Thường");
    }

    @Test
    void getAvailability_soldOut_whenAllZonesEmpty() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2).standing(10).sold(12)).create()
                .showtimeId();

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/availability");

        assertThat(body.path("sold_out").asBoolean()).isTrue();
        assertThat(body.path("zones").get(0).path("seats_left").asInt()).isZero();
        assertThat(body.path("zones").get(1).path("seats_left").asInt()).isZero();
    }

    @Test
    void getAvailability_notSoldOut_whenOneZoneHasRoom() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2).standing(10).sold(2)).create()
                .showtimeId();

        assertThat(get("/api/v1/showtimes/" + showtime + "/availability").path("sold_out").asBoolean()).isFalse();
    }

    @Test
    void getAvailability_returns404_whenShowtimeMissing() {
        assertNotFound("/api/v1/showtimes/" + UUID.randomUUID() + "/availability");
    }

    @Test
    void getAvailability_returns404_whenShowtimeDraft() {
        UUID showtime = fixtures.event().showtime(s -> s.draft().seated(2)).create().showtimeId();

        assertNotFound("/api/v1/showtimes/" + showtime + "/availability");
    }

    // ------------------------------------------------------------------ suggestSeats

    @Test
    void suggestSeats_prefersAdjacentSeatsInSameRow() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seatedRows(2, 5, null)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        List<UUID> seats = fixtures.seatIds(zone);
        // Hàng A còn 1, 3, 5 (không có 3 ghế liền nhau); hàng B trống hết.
        fixtures.sellSeat(showtime, seats.get(1));
        fixtures.sellSeat(showtime, seats.get(3));

        List<UUID> picked = searchService.suggestSeats(showtime, zone, 3);

        assertThat(picked).containsExactly(seats.get(5), seats.get(6), seats.get(7));
    }

    @Test
    void suggestSeats_fallsBackToNearestSeats_whenNoAdjacentRun() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(5)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        List<UUID> seats = fixtures.seatIds(zone);
        fixtures.sellSeat(showtime, seats.get(1));
        fixtures.sellSeat(showtime, seats.get(3));

        assertThat(searchService.suggestSeats(showtime, zone, 2)).containsExactly(seats.get(0), seats.get(2));
    }

    @Test
    void suggestSeats_includesExpiredHolds_excludesActiveHolds() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(4)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        List<UUID> seats = fixtures.seatIds(zone);
        fixtures.holdSeat(showtime, seats.get(0), NOW.minusSeconds(1));
        fixtures.holdSeat(showtime, seats.get(1), NOW.plusSeconds(300));
        fixtures.sellSeat(showtime, seats.get(2));

        // Chỉ còn ghế 1 (hết hạn giữ) và ghế 4: ít hơn n thì trả hết.
        assertThat(searchService.suggestSeats(showtime, zone, 3)).containsExactly(seats.get(0), seats.get(3));
    }

    @Test
    void suggestSeats_returnsEmpty_forStandingZone() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2).standing(50)).create().showtimeId();
        UUID standing = fixtures.zoneIds(showtime).get(1);

        assertThat(searchService.suggestSeats(showtime, standing, 2)).isEmpty();
    }

    @Test
    void suggestSeats_returnsEmpty_whenZoneNotInShowtimeOrNoFreeSeat() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(1).sold(1)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        UUID other = fixtures.zoneIds(fixtures.event().showtime(s -> s.onSale().seated(2)).create().showtimeId())
                .getFirst();

        assertThat(searchService.suggestSeats(showtime, zone, 1)).isEmpty();
        assertThat(searchService.suggestSeats(showtime, other, 1)).isEmpty();
    }

    @Test
    void suggestSeats_throws_whenNotPositive() {
        assertThatThrownBy(() -> searchService.suggestSeats(UUID.randomUUID(), UUID.randomUUID(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ Helper

    private static List<String> statuses(JsonNode zone) {
        return zone.path("seats").valueStream().map(seat -> seat.path("status").asString()).toList();
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
