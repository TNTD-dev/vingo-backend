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
    void getSeatmap_ordersSeatsByNaturalRowThenNumber() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seatedRows(10, 2, null)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        jdbc.sql("""
                UPDATE seat SET row_label = 'R' || (ascii(row_label) - ascii('A') + 1)
                WHERE zone_id = :zone
                """).param("zone", zone).update();

        JsonNode seats = get("/api/v1/showtimes/" + showtime + "/seatmap").path("zones").get(0).path("seats");

        assertThat(seats).hasSize(20);
        for (int i = 0; i < seats.size(); i++) {
            assertThat(seats.get(i).path("row").asString()).isEqualTo("R" + (i / 2 + 1));
            assertThat(seats.get(i).path("number").asInt()).isEqualTo(i % 2 + 1);
        }
    }

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
    void getSeatmap_includesStandingSeatsLeft() {
        var created = fixtures.event()
                .tier("VIP", 2_000_000).tier("Thường", 500_000)
                .showtime(s -> s.onSale().seatedRows(2, 2, "VIP").standing(100, "Thường"))
                .create();
        UUID showtime = created.showtimeId();
        List<UUID> zones = fixtures.zoneIds(showtime);
        List<UUID> seats = fixtures.seatIds(zones.get(0));
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
        assertThat(list.get(0).path("seat_id").asString()).isEqualTo(seats.get(0).toString());
        assertThat(list.get(2).path("row").asString()).isEqualTo("B");
        JsonNode standing = body.path("zones").get(1);
        assertThat(standing.path("kind").asString()).isEqualTo("STANDING");
        assertThat(standing.path("seats_left").asInt()).isEqualTo(60);
        assertThat(standing.path("tier").path("name").asString()).isEqualTo("Thường");
        assertThat(standing.path("seats").size()).isZero();
    }

    @Test
    void getSeatmap_hidesZoneWithoutEventSku() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        jdbc.sql("DELETE FROM event_zone WHERE zone_id = :z").param("z", zone).update();

        JsonNode zones = get("/api/v1/showtimes/" + showtime + "/seatmap").path("zones");

        assertThat(zones).isEmpty();
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
    void getSeatmap_returns404_whenEventNotPublished() {
        UUID showtime = fixtures.event().draft().showtime(s -> s.scheduled().onSale().seated(2)).create()
                .showtimeId();

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
        assertThat(body.path("zones").get(0).path("kind").asString()).isEqualTo("SEATED");
        assertThat(body.path("zones").get(1).path("kind").asString()).isEqualTo("STANDING");
        assertThat(body.path("zones").get(0).path("seats_left").asInt()).isEqualTo(2);
        assertThat(body.path("zones").get(0).path("tier").path("name").asString()).isEqualTo("VIP");
        assertThat(body.path("zones").get(1).path("seats_left").asInt()).isEqualTo(60);
        assertThat(body.path("zones").get(1).path("tier").path("name").asString()).isEqualTo("Thường");
    }

    @Test
    void getAvailability_hidesZonesWithoutEventSku() {
        var showtime = fixtures.event().showtime(s -> s.onSale().seated(2).standing(10)).create().showtimeId();
        var zones = fixtures.zoneIds(showtime);
        jdbc.sql("DELETE FROM event_zone WHERE zone_id = :zone")
                .param("zone", zones.getLast()).update();
        var base = "/api/v1/showtimes/" + showtime;
        assertThat(get(base + "/availability").path("zones")).hasSize(1);
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
    void getAvailability_ignoresSeatsAndStockWithoutRows() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).standing(50)).create().showtimeId();
        List<UUID> zones = fixtures.zoneIds(showtime);
        fixtures.deleteShowtimeSeat(showtime, fixtures.seatIds(zones.get(0)).get(0));
        fixtures.deleteZoneStock(showtime, zones.get(1));

        JsonNode body = get("/api/v1/showtimes/" + showtime + "/availability");

        assertThat(body.path("zones").get(0).path("seats_left").asInt()).isEqualTo(2);
        assertThat(body.path("zones").get(1).path("seats_left").asInt()).isZero();
        assertThat(body.path("sold_out").asBoolean()).isFalse();
    }

    @Test
    void getAvailability_returns404_whenEventNotPublished() {
        UUID showtime = fixtures.event().draft().showtime(s -> s.scheduled().onSale().seated(2)).create()
                .showtimeId();

        assertNotFound("/api/v1/showtimes/" + showtime + "/availability");
    }

    @Test
    void seatSuggestions_readsCurrentFreeSeatsAndZonePrice() {
        UUID showtime = fixtures.event().tier("VIP", 500_000)
                .showtime(s -> s.onSale().seated(5, "VIP")).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        List<UUID> seats = fixtures.seatIds(zone);
        fixtures.sellSeat(showtime, seats.get(0));
        fixtures.blockSeat(showtime, seats.get(1));
        fixtures.holdSeat(showtime, seats.get(2), NOW.plusSeconds(60));
        fixtures.holdSeat(showtime, seats.get(3), NOW.minusSeconds(1));
        String path = suggestions(showtime, zone, 2);

        JsonNode body = get(path);

        assertThat(body.path("showtime_id").asString()).isEqualTo(showtime.toString());
        assertThat(body.path("zone_id").asString()).isEqualTo(zone.toString());
        assertThat(body.path("quantity").asInt()).isEqualTo(2);
        assertThat(body.path("seats_left").asInt()).isEqualTo(2);
        assertThat(body.path("seats")).hasSize(2);
        for (int i = 0; i < 2; i++) {
            JsonNode seat = body.path("seats").get(i);
            assertThat(seat.path("seat_id").asString()).isEqualTo(seats.get(3 + i).toString());
            assertThat(seat.path("row").asString()).isEqualTo("A");
            assertThat(seat.path("number").asInt()).isEqualTo(4 + i);
        }
        assertThat(body.path("together").asBoolean()).isTrue();
        assertThat(body.path("unit_price").asLong()).isEqualTo(500_000);
        assertThat(body.path("total").asLong()).isEqualTo(1_000_000);
        assertThat(body.path("currency").asString()).isEqualTo("VND");
        assertThat(Instant.parse(body.path("observed_at").asString())).isEqualTo(NOW);

        fixtures.sellSeat(showtime, seats.get(4));
        JsonNode next = get(path);
        assertThat(next.path("seats_left").asInt()).isOne();
        assertThat(next.path("seats")).isEmpty();
        assertThat(next.path("together").asBoolean()).isFalse();
        assertThat(next.path("unit_price").asLong()).isEqualTo(500_000);
        assertThat(next.path("total").isNull()).isTrue();
    }

    @Test
    void seatSuggestions_rankRowsFromTheWholeZoneAfterFrontRowSellsOut() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seatedRows(3, 2, null)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        jdbc.sql("""
                UPDATE seat SET row_label = CASE row_label WHEN 'A' THEN 'R1' WHEN 'B' THEN 'R2' ELSE 'R10' END
                WHERE zone_id = :zone
                """).param("zone", zone).update();
        String path = suggestions(showtime, zone, 2);

        JsonNode front = get(path + "&position=FRONT").path("seats");
        assertThat(front).hasSize(2);
        assertThat(front.get(0).path("row").asString()).isEqualTo("R1");
        assertThat(front.get(0).path("row_rank").asInt()).isOne();
        assertThat(get(path).path("seats")).isEqualTo(front);

        JsonNode back = get(path + "&position=BACK").path("seats");
        assertThat(back).hasSize(2);
        for (JsonNode seat : back) {
            assertThat(seat.path("row").asString()).isEqualTo("R10");
            assertThat(seat.path("row_rank").asInt()).isEqualTo(3);
        }
        for (JsonNode seat : front) {
            fixtures.sellSeat(showtime, UUID.fromString(seat.path("seat_id").asString()));
        }
        assertThat(get(path + "&position=BACK").path("seats")).isEqualTo(back);
        JsonNode next = get(path).path("seats").get(0);
        assertThat(next.path("row").asString()).isEqualTo("R2");
        assertThat(next.path("row_rank").asInt()).isEqualTo(2);
    }

    @Test
    void seatSuggestions_returns404ForHiddenShowtimeMissingShowtimeOrForeignZone() {
        UUID draft = fixtures.event().draft().showtime(s -> s.onSale().seated(2)).create().showtimeId();
        var visible = seatSetup();
        assertNotFound(suggestions(draft, fixtures.zoneIds(draft).getFirst(), 1));
        assertNotFound(suggestions(UUID.randomUUID(), visible.zone(), 1));
        assertNotFound(suggestions(visible.showtime(), fixtures.zoneIds(draft).getFirst(), 1));
    }

    @Test
    void seatSuggestions_returns404WhenZoneIsAbsentFromEventLayout() {
        var c = seatSetup();
        jdbc.sql("DELETE FROM event_zone WHERE zone_id = :zone").param("zone", c.zone()).update();

        assertNotFound(suggestions(c.showtime(), c.zone(), 1));
    }

    @Test
    void seatSuggestions_rejectsStandingZoneAndInvalidParameters() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(2).standing(10)).create().showtimeId();
        var zones = fixtures.zoneIds(showtime);
        String path = "/api/v1/showtimes/" + showtime + "/seat-suggestions";
        for (String query : List.of("?quantity=1", "?zone_id=invalid&quantity=1", "?zone_id=" + zones.getFirst(),
                "?zone_id=" + zones.getFirst() + "&quantity=0", "?zone_id=" + zones.getFirst() + "&quantity=11",
                "?zone_id=" + zones.getFirst() + "&quantity=1&position=SIDE",
                "?zone_id=" + zones.getLast() + "&quantity=1")) {
            client.get().uri(path + query).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
    }

    private static String suggestions(UUID showtime, UUID zone, int quantity) {
        return "/api/v1/showtimes/" + showtime + "/seat-suggestions?zone_id=" + zone + "&quantity=" + quantity;
    }

    // ------------------------------------------------------------------ suggestSeats

    /** Suất 4 ghế cùng hàng; trả (suất, khu, ghế). */
    private record SeatSetup(UUID showtime, UUID zone, List<UUID> seats) {
    }

    private SeatSetup seatSetup() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(4)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        List<UUID> seats = fixtures.seatIds(zone);
        return new SeatSetup(showtime, zone, seats);
    }

    @Test
    void suggestSeats_includesSeat_whenItsHoldExpired() {
        var c = seatSetup();
        fixtures.holdSeat(c.showtime(), c.seats().get(1), NOW.minus(Duration.ofMinutes(1)));

        assertThat(searchService.suggestSeats(c.showtime(), c.zone(), 4)).containsExactlyInAnyOrderElementsOf(c.seats());
    }

    @Test
    void suggestSeats_returnsAllSeats_whenRowIsFree() {
        var c = seatSetup();

        assertThat(searchService.suggestSeats(c.showtime(), c.zone(), 4)).containsExactlyInAnyOrderElementsOf(c.seats());
    }

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
