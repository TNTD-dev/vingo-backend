package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import tools.jackson.databind.json.JsonMapper;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** GET /showtimes/{id}/seatmap.svg: công khai, chỉ trả SVG của sơ đồ PUBLISHED thuộc suất hiển thị được. */
class SeatmapSvgTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 1000\"><g id=\"A\"/></svg>";
    private static final String OTHER_SVG = SVG.replace("id=\"A\"", "id=\"OTHER\"");

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

    @Test
    void getSeatmapSvg_returnsSvgOfShowtimeLayout_withoutToken() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).layoutSvg(SVG)).create().showtimeId();
        UUID other = fixtures.event().showtime(s -> s.salesNotOpen().seated(3).layoutSvg(OTHER_SVG)).create().showtimeId();

        var result = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("image/svg+xml")
                .expectBody(String.class).returnResult();

        assertThat(result.getResponseBody()).isEqualTo(SVG);
        String cache = result.getResponseHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertThat(cache).contains("no-cache").doesNotContain("max-age");
        assertThat(result.getResponseHeaders().getETag()).isNotBlank();
    }

    @Test
    void getSeatmapSvg_returns304_whenEtagStillMatches() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).layoutSvg(SVG)).create().showtimeId();
        String etag = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getETag();

        client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg")
                .header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isNotModified();
    }

    @Test
    void getSeatmapSvg_returnsNewSvg_whenShowtimeLayoutChanged() {
        UUID showtime = fixtures.event().showtime(s -> s.salesNotOpen().seated(3).layoutSvg(SVG)).create().showtimeId();
        UUID other = fixtures.event().showtime(s -> s.salesNotOpen().seated(3).layoutSvg(OTHER_SVG)).create().showtimeId();
        String etag = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .returnResult(String.class).getResponseHeaders().getETag();
        jdbc.sql("UPDATE showtime SET seat_layout_id = (SELECT seat_layout_id FROM showtime WHERE id = :other) "
                + "WHERE id = :id").param("other", other).param("id", showtime).update();

        var body = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg")
                .header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isEqualTo(OTHER_SVG);
    }

    @Test
    void getSeatmapSvg_returns404_whenShowtimeDoesNotExist() {
        assertNotFound(UUID.randomUUID());
    }

    @Test
    void getSeatmapSvg_returns404_whenShowtimeIsDraft() {
        UUID showtime = fixtures.event().showtime(s -> s.draft().seated(3).layoutSvg(SVG)).create().showtimeId();

        assertNotFound(showtime);
    }

    @Test
    void getSeatmapSvg_returns404_whenEventIsNotPublished() {
        UUID showtime = fixtures.event().draft().showtime(s -> s.onSale().seated(3).layoutSvg(SVG)).create().showtimeId();

        assertNotFound(showtime);
    }

    @Test
    void getSeatmapSvg_returns404_whenLayoutHasNoSvg() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).draftLayoutWithoutSvg()).create().showtimeId();

        assertNotFound(showtime);
    }

    @Test
    void getSeatmapSvg_returns404_whenLayoutIsNotPublished() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).draftLayout()).create().showtimeId();

        assertNotFound(showtime);
    }

    private void assertNotFound(UUID showtimeId) {
        String body = client.get().uri("/api/v1/showtimes/" + showtimeId + "/seatmap.svg").exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonMapper.builder().build().readTree(body).path("code").asString()).isEqualTo("NOT_FOUND");
    }
}
