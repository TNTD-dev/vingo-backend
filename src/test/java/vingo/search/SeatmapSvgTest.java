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

        assertThat(result.getResponseBody()).contains("id=\"A\"").doesNotContain("id=\"OTHER\"");
        String cache = result.getResponseHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertThat(cache).contains("no-cache").doesNotContain("max-age");
        assertThat(result.getResponseHeaders().getETag()).isNotBlank();
    }

    @Test
    void getSeatmapSvg_includesPitchBehindSeats_whenVenueOverviewMatches() {
        String detail = SVG.replace("id=\"A\"", "id=\"ZONE__A__SEATED\"");
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3).layoutSvg(detail)).create().showtimeId();
        String overview = SVG.replace("id=\"A\"", "id=\"PITCH\"");
        jdbc.sql("""
                UPDATE venue_map SET svg = :svg WHERE id = (
                    SELECT sm.venue_map_id FROM showtime sh JOIN seat_map sm ON sm.id = sh.seat_map_id
                    WHERE sh.id = :id
                )
                """).param("svg", overview).param("id", showtime).update();

        String response = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(response).containsSubsequence("id=\"PITCH\"", "id=\"ZONE__A__SEATED\"");
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
        String etag = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .returnResult(String.class).getResponseHeaders().getETag();
        jdbc.sql("UPDATE seat_map SET svg = :svg WHERE id = (SELECT seat_map_id FROM showtime WHERE id = :id)")
                .param("svg", OTHER_SVG).param("id", showtime).update();

        var body = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg")
                .header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("id=\"OTHER\"").doesNotContain("id=\"A\"");
    }

    @Test
    void getSeatmapSvg_returns404_whenShowtimeDoesNotExist() {
        assertNotFound(UUID.randomUUID());
    }

    @Test
    void getSeatmapSvg_returns404_whenEventIsNotPublished() {
        UUID showtime = fixtures.event().draft().showtime(s -> s.onSale().seated(3).layoutSvg(SVG)).create().showtimeId();

        assertNotFound(showtime);
    }

    private void assertNotFound(UUID showtimeId) {
        String body = client.get().uri("/api/v1/showtimes/" + showtimeId + "/seatmap.svg").exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonMapper.builder().build().readTree(body).path("code").asString()).isEqualTo("NOT_FOUND");
    }
}
