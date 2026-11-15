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
    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><g id=\"A\"/></svg>";

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
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        UUID other = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        insertSvg(showtime, SVG);
        insertSvg(other, "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"OTHER\"/></svg>");

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
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        insertSvg(showtime, SVG);
        String etag = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getETag();

        client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg")
                .header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isNotModified();
    }

    @Test
    void getSeatmapSvg_returnsNewSvg_whenShowtimeLayoutChanged() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        UUID other = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        insertSvg(showtime, SVG);
        String otherSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"OTHER\"/></svg>";
        insertSvg(other, otherSvg);
        String etag = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg").exchange()
                .returnResult(String.class).getResponseHeaders().getETag();
        jdbc.sql("UPDATE showtime SET seat_layout_id = (SELECT seat_layout_id FROM showtime WHERE id = :other) "
                + "WHERE id = :id").param("other", other).param("id", showtime).update();

        var body = client.get().uri("/api/v1/showtimes/" + showtime + "/seatmap.svg")
                .header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isEqualTo(otherSvg);
    }

    @Test
    void getSeatmapSvg_returns404_whenShowtimeDoesNotExist() {
        assertNotFound(UUID.randomUUID());
    }

    @Test
    void getSeatmapSvg_returns404_whenShowtimeIsDraft() {
        UUID showtime = fixtures.event().showtime(s -> s.draft().seated(3)).create().showtimeId();
        insertSvg(showtime, SVG);

        assertNotFound(showtime);
    }

    @Test
    void getSeatmapSvg_returns404_whenEventIsNotPublished() {
        UUID showtime = fixtures.event().draft().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        insertSvg(showtime, SVG);

        assertNotFound(showtime);
    }

    @Test
    void getSeatmapSvg_returns404_whenLayoutHasNoSvg() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();

        assertNotFound(showtime);
    }

    @Test
    void getSeatmapSvg_returns404_whenLayoutIsNotPublished() {
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(3)).create().showtimeId();
        insertSvg(showtime, SVG);
        jdbc.sql("UPDATE seat_layout SET status = 'DRAFT', published_at = NULL WHERE id = "
                + "(SELECT seat_layout_id FROM showtime WHERE id = :id)").param("id", showtime).update();

        assertNotFound(showtime);
    }

    private void insertSvg(UUID showtimeId, String svg) {
        jdbc.sql("""
                INSERT INTO seat_layout_svg (seat_layout_id, schema_version, svg_content,
                                             view_box_x, view_box_y, view_box_width, view_box_height)
                SELECT seat_layout_id, 1, :svg, 0, 0, 100, 100 FROM showtime WHERE id = :id
                """).param("svg", svg).param("id", showtimeId).update();
    }

    private void assertNotFound(UUID showtimeId) {
        String body = client.get().uri("/api/v1/showtimes/" + showtimeId + "/seatmap.svg").exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonMapper.builder().build().readTree(body).path("code").asString()).isEqualTo("NOT_FOUND");
    }
}
