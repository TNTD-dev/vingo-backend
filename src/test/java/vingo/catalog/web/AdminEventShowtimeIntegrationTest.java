package vingo.catalog.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import vingo.catalog.VenueService;
import vingo.catalog.internal.VenueRepository.VenueData;
import vingo.search.SearchService;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;
import vingo.support.IntegrationTest;

class AdminEventShowtimeIntegrationTest extends IntegrationTest {
    private static final String ADMIN = "/api/v1/admin";
    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");
    private static final String SMALL_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <rect id="ZONE__A-T1__STANDING__10" width="50" height="50"/>
            </svg>
            """;
    private final SearchService search;
    private final StringRedisTemplate redis;
    private final VenueService venues;
    private final TransactionTemplate tx;
    private final vingo.catalog.internal.SeatLayoutImportService layouts;
    private String token;

    @Autowired
    AdminEventShowtimeIntegrationTest(SearchService search, StringRedisTemplate redis,
            VenueService venues, PlatformTransactionManager transactions,
            vingo.catalog.internal.SeatLayoutImportService layouts) {
        this.search = search;
        this.redis = redis;
        this.venues = venues;
        this.tx = new TransactionTemplate(transactions);
        this.layouts = layouts;
    }

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        token = tokenFor(Role.ADMIN);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void eventJourney_selectsOwnHallsNamedPublishedLayoutsAndCreatesShowtimesAtTwoVenues() throws Exception {
        String prefix = "EV3-" + UUID.randomUUID();
        UUID venueA = createVenue(prefix + " A", "HN");
        UUID venueB = createVenue(prefix + " B", "DN");
        UUID hallA = venueA;
        UUID hallB = venueB;
        String listed = client.get().uri(builder -> builder.path(ADMIN + "/venues")
                        .queryParam("q", prefix).build()).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(listed).contains(venueA.toString(),venueB.toString()).doesNotContain("halls");

        String svg = vingo.support.VenueImportHttpFixture.SAMPLE_DETAIL;
        UUID layoutA = importLayout(hallA, "  Bóng đá  ", svg);
        UUID layoutB = importLayout(hallB, "Concert – sân khấu đầu B", svg);
        String overview = get(ADMIN + "/seat-layouts/" + layoutA);
        assertThat(JsonPath.<String>read(overview, "$.name")).isEqualTo("Bóng đá");
        assertThat(JsonPath.<Integer>read(overview, "$.seat_count")).isEqualTo(4);
        assertThat(get(ADMIN + "/seat-layouts/" + layoutA + "/svg")).contains("ZONE__B3-T1__SEATED");
        String zone = JsonPath.<List<String>>read(overview, "$.zones[?(@.code == 'B3-T1')].id").getFirst();
        assertThat(JsonPath.<String>read(get(ADMIN + "/seat-layouts/" + layoutA + "/zones/" + zone),
                "$.pitch_side_svg_id")).isEqualTo("PITCH_SIDE__B3-T1");
        String layouts = get(ADMIN + "/venues/" + hallA + "/seat-layouts");
        assertThat(JsonPath.<String>read(layouts, "$.data[0].name")).isEqualTo("Bóng đá");
        UUID event = id(post(ADMIN + "/events", Map.of("title", prefix, "category", "CONCERT"), 201));
        publish(layoutA);
        publish(layoutB);
        UUID showA = createShowtime(event, hallA, layoutA);
        UUID showB = createShowtime(event, hallB, layoutB);
        String detail = get(ADMIN + "/events/" + event);
        assertThat(JsonPath.<List<String>>read(detail, "$.showtimes[*].status")).containsOnly("DRAFT");
        assertThat(JsonPath.<List<String>>read(detail, "$.showtimes[*].venue.id"))
                .containsExactlyInAnyOrder(venueA.toString(), venueB.toString());
        assertInventory(showA, layoutA, 4);
        assertInventory(showB, layoutB, 4);

        UUID layoutA2 = importLayout(hallA, "Bóng đá", SMALL_SVG.replace("STANDING__10", "STANDING__11"));
        publish(layoutA2);
        assertInventory(showA, layoutA, 4);
        client.patch().uri(ADMIN + "/showtimes/{id}", showA).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("seat_layout_id", layoutA2))
                .exchange().expectStatus().isOk();
        assertInventory(showA, layoutA2, 0);
        assertInventory(showB, layoutB, 4);
        assertThat(jdbc.sql("SELECT count(*) FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = :id")
                .param("id", layoutA).query(Integer.class).single()).isEqualTo(4);
        clock.set(NOW.plusSeconds(2 * 86400));
        client.patch().uri(ADMIN + "/showtimes/{id}", showA).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("seat_layout_id", layoutA))
                .exchange().expectStatus().isOk();
        assertInventory(showA, layoutA, 4);
    }

    @Test
    void selectedFootballLayout_acceptsPricingBeforeShowtimeAndOnlyOneActiveShow() {
        UUID venue = createVenue("Sân " + UUID.randomUUID(), "HN");
        UUID otherVenue = createVenue("Sân " + UUID.randomUUID(), "DN");
        UUID layout = importLayout(venue, "Bóng đá", vingo.support.VenueImportHttpFixture.SAMPLE_DETAIL);
        UUID otherLayout = importLayout(otherVenue, "Bóng đá", vingo.support.VenueImportHttpFixture.SAMPLE_DETAIL);
        publish(layout);
        publish(otherLayout);
        String path = ADMIN + "/events";
        var selection = Map.of("title", "Trận đấu " + UUID.randomUUID(), "category", "FOOTBALL",
                "venue_id", venue, "seat_layout_id", layout);
        post(path, Map.of("title", "Sai loại", "category", "CONCERT", "venue_id", venue,
                "seat_layout_id", layout), 400);
        UUID event = id(post(path, selection, 201));
        path += "/" + event;
        var tierResponse = client.put().uri(path + "/price-tiers").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(List.of(Map.of("name", "A", "price", 100000),
                        Map.of("name", "B", "price", 100000))).exchange();
        tierResponse.expectStatus().isOk();
        String saved = get(path);
        List<String> tierIds = JsonPath.read(saved, "$.price_tiers[*].id");
        List<String> colors = JsonPath.read(saved, "$.price_tiers[*].color");
        assertThat(colors).hasSize(2).containsOnly(colors.getFirst());
        List<String> zoneIds = JsonPath.read(get(ADMIN + "/seat-layouts/" + layout), "$.zones[*].id");
        var prices = List.of(
                Map.of("zone_id", zoneIds.get(0), "price_tier_id", tierIds.get(0), "display_name", "Khán đài mới"),
                Map.of("zone_id", zoneIds.get(1), "price_tier_id", tierIds.get(1)));
        client.put().uri(path + "/zone-prices").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(prices).exchange().expectStatus().isOk();
        client.put().uri(path + "/zone-prices").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("zone_id", zoneIds.getFirst(), "price_tier_id", tierIds.getFirst(),
                                "display_name", "Không được lưu"),
                        Map.of("zone_id", UUID.randomUUID(), "price_tier_id", tierIds.getFirst())))
                .exchange().expectStatus().isBadRequest();
        assertThat(get(path)).contains("Khán đài mới").doesNotContain("Không được lưu");
        assertThat(get(ADMIN + "/seat-layouts/" + layout)).doesNotContain("Khán đài mới");
        post(path + "/showtimes", showtimeBody(otherVenue, otherLayout), 400);
        UUID show = createShowtime(event, venue, layout);
        post(path + "/showtimes", showtimeBody(venue, layout), 400);
        post(path + "/publish", null, 200);
        client.get().uri("/api/v1/showtimes/{id}/availability", show).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.zones[?(@.name == 'Khán đài mới')].code").isNotEmpty();
        post(ADMIN + "/showtimes/" + show + "/cancel", Map.of("reason", "Thử hủy suất cuối"), 409);
        post(path + "/cancel", Map.of("reason", "Thử hủy trước giờ bán"), 200);
        String cancelled = get(path);
        assertThat(JsonPath.<String>read(cancelled, "$.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<List<String>>read(cancelled, "$.showtimes[*].status")).containsExactly("CANCELLED");
    }

    @Test
    void layoutName_canRepeatAcrossVersionsAndRenameOnlyDraftWithoutChangingSvg() {
        UUID hall = catalog.event().venueId();
        UUID first = importLayout(hall, "Bóng đá", SMALL_SVG);
        UUID second = importLayout(hall, "Bóng đá", SMALL_SVG);
        String before = get(ADMIN + "/seat-layouts/" + first + "/svg");
        client.patch().uri(ADMIN + "/seat-layouts/{id}", first).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "  Concert  "))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.name").isEqualTo("Concert");
        assertThat(get(ADMIN + "/seat-layouts/" + first + "/svg")).isEqualTo(before);
        assertThat(JsonPath.<String>read(get(ADMIN + "/seat-layouts/" + second), "$.name")).isEqualTo("Bóng đá");
        publish(first);
        client.patch().uri(ADMIN + "/seat-layouts/{id}", first).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Không được sửa"))
                .exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("SEAT_LAYOUT_LOCKED");
        assertThat(JsonPath.<String>read(get(ADMIN + "/seat-layouts/" + first), "$.name")).isEqualTo("Concert");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void layoutName_returns400ForBlankName(String name) {
        UUID hall = catalog.event().venueId();
        upload(hall, name, SMALL_SVG).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        UUID layout = importLayout(hall, "Tên hợp lệ", SMALL_SVG);
        client.patch().uri(ADMIN + "/seat-layouts/{id}", layout).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", name))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void layoutName_returns400ForMissingNullOrTooLongNameAnd404ForMissingLayout() {
        UUID hall = catalog.event().venueId();
        upload(hall, null, SMALL_SVG).expectStatus().isBadRequest();
        upload(hall, "A".repeat(201), SMALL_SVG).expectStatus().isBadRequest();
        UUID layout = importLayout(hall, "A".repeat(200), SMALL_SVG);
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\"" + "A".repeat(201) + "\"}")) {
            client.patch().uri(ADMIN + "/seat-layouts/{id}", layout).headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
        client.patch().uri(ADMIN + "/seat-layouts/{id}", UUID.randomUUID()).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Tên hợp lệ"))
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void layoutWrites_return404ForDeactivatedVenue() {
        UUID venue = createVenue("EV3-" + UUID.randomUUID(), "HN");
        UUID hall = venue;
        UUID layout = importLayout(hall, "Tên hợp lệ", SMALL_SVG);
        client.delete().uri(ADMIN + "/venues/{id}", venue).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
        client.post().uri(ADMIN+"/venue-imports").headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("target_venue_id",hall)).exchange().expectStatus().isNotFound();
        client.post().uri(ADMIN + "/seat-layouts/{id}/publish", layout).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNotFound();
        client.patch().uri(ADMIN + "/seat-layouts/{id}", layout).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Tên mới"))
                .exchange().expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDED", "CANCELLED"})
    void showtimeWrites_rejectClosedEventsAndPreserveInventory(String status) {
        var event = catalog.event(EventSpec.defaults().withCity("HN").withStatus(status));
        var showtime = catalog.showtime(event, Window.notOpenYet());
        var before = showtimeSnapshot(showtime.id());
        client.post().uri(ADMIN + "/events/{id}/showtimes", event.id()).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(showtimeBody(event.venueId(), event.layoutId()))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("EVENT_LOCKED");
        client.patch().uri(ADMIN + "/showtimes/{id}", showtime.id()).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("starts_at", atDays(8)))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("EVENT_LOCKED");
        assertThat(showtimeSnapshot(showtime.id())).isEqualTo(before);
        assertInventory(showtime.id(), event.layoutId(), 10);
        assertThat(jdbc.sql("SELECT count(*) FROM showtime WHERE event_id = :id")
                .param("id", event.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void showtimeChanges_refreshEventListsTimesAndLayoutCacheAfterCommit() {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        assertThat(search.getEvent(event.id()).showtimes()).isEmpty();
        UUID showtime = createShowtime(event.id(), event.venueId(), event.layoutId());
        assertThat(search.getEvent(event.id()).showtimes()).hasSize(1);
        search.listEventShowtimes(event.id());
        search.getSeatmap(showtime, null);
        var inventory = jdbc.sql("SELECT * FROM showtime_seat WHERE showtime_id = :id ORDER BY seat_id")
                .param("id", showtime).query().listOfRows();
        client.patch().uri(ADMIN + "/showtimes/{id}", showtime).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("sale_opens_at", atDays(3)))
                .exchange().expectStatus().isOk();
        assertThat(search.getEvent(event.id()).showtimes().getFirst().saleOpensAt())
                .isEqualTo(NOW.atOffset(ZoneOffset.UTC).plusDays(3));
        assertThat(search.listEventShowtimes(event.id()).getFirst().saleOpensAt())
                .isEqualTo(NOW.atOffset(ZoneOffset.UTC).plusDays(3));
        clock.set(NOW.plusSeconds(2 * 86400));
        assertThat(search.getSeatmap(showtime, null).status().name()).isEqualTo("SCHEDULED");
        clock.set(NOW);
        var other = catalog.event(EventSpec.defaults().withCity("DN"));
        client.patch().uri(ADMIN + "/showtimes/{id}", showtime).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("hall_id", other.venueId(), "seat_layout_id", other.layoutId()))
                .exchange().expectStatus().isOk();
        assertThat(search.getEvent(event.id()).showtimes().getFirst().venue().city()).isEqualTo("DN");
        assertThat(search.getSeatmap(showtime, null).zones().stream().map(zone -> zone.zoneId()).toList())
                .containsExactlyInAnyOrder(other.seatedZoneId(), other.standingZoneId());
        assertThat(jdbc.sql("SELECT * FROM showtime_seat WHERE showtime_id = :id ORDER BY seat_id")
                .param("id", showtime).query().listOfRows()).isNotEqualTo(inventory);
        assertInventory(showtime, other.layoutId(), 10);
    }

    @Test
    void venueChanges_refreshSearchOnlyAfterCommitAndRollbackDoesNotInvalidate() {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        catalog.showtime(event, Window.notOpenYet());
        String oldName = search.getEvent(event.id()).venue().orElseThrow().name();
        client.patch().uri(ADMIN + "/venues/{id}", event.venueId()).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Sân đã đổi tên"))
                .exchange().expectStatus().isOk();
        assertThat(search.getEvent(event.id()).venue().orElseThrow().name()).isEqualTo("Sân đã đổi tên");
        assertThat(oldName).isNotEqualTo("Sân đã đổi tên");
        String generation = redis.opsForValue().get("vingo:search:gen:venue");
        tx.executeWithoutResult(status -> {
            venues.update(event.venueId(), new VenueData("Tên rollback", null, null, null,
                    null, null, null, null));
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get("vingo:search:gen:venue")).isEqualTo(generation);
        assertThat(search.getEvent(event.id()).venue().orElseThrow().name()).isEqualTo("Sân đã đổi tên");
    }

    @Test
    void showtimeCreationRollback_preservesDatabaseAndDoesNotInvalidateCache() {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        assertThat(search.getEvent(event.id()).showtimes()).isEmpty();
        String key = "vingo:search:gen:event:" + event.id();
        String generation = redis.opsForValue().get(key);
        tx.executeWithoutResult(status -> {
            createShowtimeDirect(event.id(), event.venueId(), event.layoutId());
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM showtime WHERE event_id = :id")
                .param("id", event.id()).query(Integer.class).single()).isZero();
        assertThat(redis.opsForValue().get(key)).isEqualTo(generation);
        assertThat(search.getEvent(event.id()).showtimes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HISTORY", "STANDING_HELD", "STANDING_SOLD"})
    void layoutChange_rejectsExistingOrdersOrAllocatedStockWithoutResettingAnything(String allocation) {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        var showtime = catalog.showtime(event, Window.notOpenYet());
        if (allocation.equals("HISTORY")) {
            catalog.order(catalog.buyer(), showtime.id(), "EXPIRED", NOW.atOffset(ZoneOffset.UTC).plusMinutes(10));
        } else {
            catalog.setZoneStock(showtime.id(), event.standingZoneId(),
                    allocation.equals("STANDING_SOLD") ? 1 : 0,
                    allocation.equals("STANDING_HELD") ? 1 : 0);
        }
        var stock = jdbc.sql("SELECT * FROM showtime_zone_stock WHERE showtime_id = :id ORDER BY zone_id")
                .param("id", showtime.id()).query().listOfRows();
        var before = showtimeSnapshot(showtime.id());
        UUID target = importLayout(event.venueId(), "Phiên bản mới", SMALL_SVG);
        publish(target);
        client.patch().uri(ADMIN + "/showtimes/{id}", showtime.id()).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("seat_layout_id", target))
                .exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("SHOWTIME_LOCKED");
        assertThat(showtimeSnapshot(showtime.id())).isEqualTo(before);
        assertThat(jdbc.sql("SELECT * FROM showtime_zone_stock WHERE showtime_id = :id ORDER BY zone_id")
                .param("id", showtime.id()).query().listOfRows()).isEqualTo(stock);
        assertThat(jdbc.sql("SELECT count(*) FROM showtime_seat WHERE showtime_id = :id")
                .param("id", showtime.id()).query(Integer.class).single()).isEqualTo(10);
    }

    @Test
    void layoutPublish_refreshesEveryReferencedEventAndDoesNotInvalidateOnRollback() {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        var showtime = catalog.showtime(event, Window.notOpenYet());
        search.getEvent(event.id());
        search.getSeatmap(showtime.id(), null);
        String key = "vingo:search:gen:event:" + event.id();
        String generation = redis.opsForValue().get(key);
        tx.executeWithoutResult(status -> {
            layouts.publish(event.layoutId());
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(key)).isEqualTo(generation);
        jdbc.sql("UPDATE event SET title = 'Tên mới sau publish layout' WHERE id = :id")
                .param("id", event.id()).update();
        publish(event.layoutId());
        assertThat(search.getEvent(event.id()).title()).isEqualTo("Tên mới sau publish layout");
        assertThat(redis.opsForValue().get("vingo:search:gen:showtime:" + showtime.id())).isEqualTo("1");
    }

    private void createShowtimeDirect(UUID event, UUID hall, UUID layout) {
        var now = NOW.atOffset(ZoneOffset.UTC);
        layouts.createShowtime(event, new vingo.catalog.internal.SeatLayoutRepository.ShowtimeData(
                layout, now.plusDays(7), now.plusDays(7).plusHours(2), now.plusDays(2), now.plusDays(6)));
    }

    private UUID createVenue(String name, String city) {
        return id(post(ADMIN + "/venues", Map.of("name", name, "type", "STADIUM",
                "address", "Địa chỉ kiểm thử", "city", city), 201));
    }

    private UUID importLayout(UUID venue,String name,String svg) {
        return vingo.support.VenueImportHttpFixture.importLayout(client,token,venue,name,svg);
    }

    private RestTestClient.ResponseSpec upload(UUID venue,String name,String svg) {
        return vingo.support.VenueImportHttpFixture.importResponse(client,token,venue,name,svg);
    }

    private void publish(UUID layout) {
        post(ADMIN + "/seat-layouts/" + layout + "/publish", null, 200);
    }

    private UUID createShowtime(UUID event, UUID hall, UUID layout) {
        return id(post(ADMIN + "/events/" + event + "/showtimes", showtimeBody(hall, layout), 201));
    }

    private Map<String, Object> showtimeBody(UUID hall, UUID layout) {
        return Map.of("hall_id", hall, "seat_layout_id", layout, "sale_opens_at", atDays(2),
                "sale_closes_at", atDays(6), "starts_at", atDays(7),
                "ends_at", NOW.atOffset(ZoneOffset.UTC).plusDays(7).plusHours(2).toString());
    }

    private String post(String path, Map<String, ?> body, int status) {
        return client.post().uri(path).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body)
                .exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
    }

    private String get(String path) {
        return client.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static UUID id(String body) { return UUID.fromString(JsonPath.read(body, "$.id")); }
    private static String atDays(int days) { return NOW.atOffset(ZoneOffset.UTC).plusDays(days).toString(); }

    private Map<String, Object> showtimeSnapshot(UUID showtime) {
        return jdbc.sql("SELECT * FROM showtime WHERE id = :id").param("id", showtime).query().singleRow();
    }

    private void assertInventory(UUID showtime, UUID layout, int seatCount) {
        var expected = jdbc.sql("SELECT s.id FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = :id")
                .param("id", layout).query(UUID.class).list();
        assertThat(expected).hasSize(seatCount);
        assertThat(jdbc.sql("SELECT seat_id FROM showtime_seat WHERE showtime_id = :id")
                .param("id", showtime).query(UUID.class).list()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(jdbc.sql("SELECT count(*) FROM showtime_seat WHERE showtime_id = :id AND status <> 'AVAILABLE'")
                .param("id", showtime).query(Integer.class).single()).isZero();
        var expectedStock = jdbc.sql("SELECT id AS zone_id, capacity, 0 AS sold, 0 AS held FROM zone WHERE seat_layout_id = :id AND kind = 'STANDING' ORDER BY id")
                .param("id", layout).query().listOfRows();
        assertThat(jdbc.sql("SELECT zone_id, capacity, sold, held FROM showtime_zone_stock WHERE showtime_id = :id ORDER BY zone_id")
                .param("id", showtime).query().listOfRows()).containsExactlyElementsOf(expectedStock);
    }
}
