package vingo.catalog.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.jayway.jsonpath.JsonPath;
import tools.jackson.databind.json.JsonMapper;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

/** Nghiệm thu CP7 qua HTTP thật; chỉ sự kiện nền được tạo trực tiếp vì API sự kiện thuộc bước sau. */
class AdminVenueAcceptanceTest extends IntegrationTest {

    private static final String ADMIN = "/api/v1/admin";
    private static final String LAYOUTS = ADMIN + "/seat-layouts/";
    private final JsonMapper json;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    AdminVenueAcceptanceTest(JsonMapper json, @Value("${local.server.port}") int port) {
        this.json = json;
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void resetClock() {
        clock.reset();
        http.close();
    }

    @Test
    void venueLifecycle_preservesPublishedInventoryAndExportsAcrossLayoutVersions() throws Exception {
        Instant now = Instant.parse("2026-11-12T06:00:00Z");
        clock.set(now);
        String token = tokenFor(Role.ADMIN);
        String venueName = "CP7-" + UUID.randomUUID();
        UUID venue = id(request("POST", ADMIN + "/venues", token, Map.of(
                "name", venueName, "type", "STADIUM", "address", "Địa chỉ kiểm thử CP7", "city", "HN"), 201));
        UUID hall = id(request("POST", ADMIN + "/venues/" + venue + "/halls", token,
                Map.of("name", "Sân chính"), 201));
        request("PATCH", ADMIN + "/venues/" + venue, token, Map.of("info", Map.of("cp", "CP7")), 200);
        assertThat(JsonPath.<List<String>>read(request("GET", ADMIN + "/venues?q=" + venueName,
                token, null, 200), "$.data[*].id")).containsExactly(venue.toString());

        String source = Files.readString(Path.of("sources/LAYOUT__THIEN-TRUONG.svg"));
        UUID v1 = importSvg(token, hall, source);
        String overviewV1 = request("GET", LAYOUTS + v1, token, null, 200);
        assertThat(JsonPath.<String>read(overviewV1, "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<Integer>read(overviewV1, "$.zone_count")).isEqualTo(27);
        assertThat(JsonPath.<Integer>read(overviewV1, "$.seat_count")).isEqualTo(6699);
        assertThat(new BigDecimal(JsonPath.read(overviewV1, "$.view_box.width").toString()))
                .isEqualByComparingTo("1600");
        assertThat(new BigDecimal(JsonPath.read(overviewV1, "$.view_box.height").toString()))
                .isEqualByComparingTo("1200");

        UUID seated = zoneId(overviewV1, "B3-T1");
        UUID standingV1 = zoneId(overviewV1, "B11-T2");
        String seatedDetail = request("GET", LAYOUTS + v1 + "/zones/" + seated, token, null, 200);
        String standingDetail = request("GET", LAYOUTS + v1 + "/zones/" + standingV1, token, null, 200);
        assertThat(JsonPath.<String>read(seatedDetail, "$.pitch_side_svg_id")).isEqualTo("PITCH_SIDE__B3-T1");
        assertThat(JsonPath.<Integer>read(standingDetail, "$.capacity")).isEqualTo(1000);
        assertThat(standingDetail).doesNotContain("\"rows\"");
        String svgV1 = request("GET", LAYOUTS + v1 + "/svg", token, null, 200);
        assertThat(svgV1).contains("ZONE__B3-T1__SEATED", "PITCH_SIDE__B3-T1");
        String zonesCsv = request("GET", LAYOUTS + v1 + "/zones.csv", token, null, 200);
        String seatsCsv = request("GET", LAYOUTS + v1 + "/seats.csv", token, null, 200);
        assertCsvMatchesStoredLayout(v1, zonesCsv, seatsCsv);

        UUID event = jdbc.sql("""
                INSERT INTO event (title, category, status)
                VALUES ('CP7 fixture', 'FOOTBALL', 'DRAFT') RETURNING id
                """).query(UUID.class).single();
        String createPath = ADMIN + "/events/" + event + "/showtimes";
        var bodyV1 = showtimeBody(hall, v1, now);
        assertCode(request("POST", createPath, token, bodyV1, 422), "INVALID_SEAT_LAYOUT");
        assertThat(jdbc.sql("SELECT count(*) FROM showtime WHERE event_id = :id")
                .param("id", event).query(Integer.class).single()).isZero();
        String publishedV1 = request("POST", LAYOUTS + v1 + "/publish", token, null, 200);
        assertThat(JsonPath.<String>read(publishedV1, "$.status")).isEqualTo("PUBLISHED");
        UUID showtimeA = id(request("POST", createPath, token, bodyV1, 201));
        assertInventory(showtimeA, v1, standingV1, 1000);

        String changedSource = source.replace("ZONE__B11-T2__STANDING__1000", "ZONE__B11-T2__STANDING__1001");
        assertThat(changedSource).isNotEqualTo(source);
        UUID v2 = importSvg(token, hall, changedSource);
        request("POST", LAYOUTS + v2 + "/publish", token, null, 200);
        String overviewV2 = request("GET", LAYOUTS + v2, token, null, 200);
        UUID standingV2 = zoneId(overviewV2, "B11-T2");
        UUID showtimeB = id(request("POST", createPath, token, showtimeBody(hall, v2, now), 201));
        assertInventory(showtimeA, v1, standingV1, 1000);
        assertInventory(showtimeB, v2, standingV2, 1001);
        assertThat(request("GET", LAYOUTS + v1 + "/svg", token, null, 200)).isEqualTo(svgV1);
        assertThat(request("GET", LAYOUTS + v1 + "/zones.csv", token, null, 200)).isEqualTo(zonesCsv);
        assertThat(request("GET", LAYOUTS + v1 + "/seats.csv", token, null, 200)).isEqualTo(seatsCsv);
        assertCode(request("GET", LAYOUTS + v1 + "/zones/" + standingV2, token, null, 404), "NOT_FOUND");

        clock.set(now.plus(Duration.ofDays(2)));
        assertCode(request("PATCH", ADMIN + "/showtimes/" + showtimeA, token,
                Map.of("seat_layout_id", v2), 409), "SHOWTIME_LOCKED");
        assertInventory(showtimeA, v1, standingV1, 1000);
        String republished = request("POST", LAYOUTS + v1 + "/publish", token, null, 200);
        assertThat(JsonPath.<String>read(republished, "$.published_at"))
                .isEqualTo(JsonPath.<String>read(publishedV1, "$.published_at"));
        assertCode(request("DELETE", ADMIN + "/venues/" + venue, token, null, 409), "VENUE_IN_USE");

        clock.set(now.plus(Duration.ofDays(7)).plus(Duration.ofHours(2)));
        request("DELETE", ADMIN + "/venues/" + venue, token, null, 204);
        assertThat(JsonPath.<List<?>>read(request("GET", ADMIN + "/venues?q=" + venueName,
                token, null, 200), "$.data")).isEmpty();
        assertCode(request("POST", ADMIN + "/venues/" + venue + "/halls", token,
                Map.of("name", "Không còn hoạt động"), 404), "NOT_FOUND");

        writeHandoffSamples(Map.of(
                "overview-v1.json", request("GET", LAYOUTS + v1, token, null, 200),
                "overview-v2.json", overviewV2,
                "seated-zone-v1.json", seatedDetail, "standing-zone-v1.json", standingDetail,
                "layout-v1.svg", svgV1, "zones-v1.csv", zonesCsv, "seats-v1.csv", seatsCsv,
                "scenario.json", json.writeValueAsString(Map.of(
                        "venue_id", venue, "hall_id", hall, "event_id", event,
                        "showtime_a", showtimeA, "showtime_b", showtimeB,
                        "layout_v1", v1, "layout_v2", v2, "create_showtime", bodyV1))));
    }

    static Stream<Arguments> layoutOperations() {
        String layout = LAYOUTS + UUID.randomUUID();
        String hall = ADMIN + "/halls/" + UUID.randomUUID() + "/seat-layouts";
        return Stream.of(
                Arguments.of("POST", hall + "/import"), Arguments.of("GET", hall),
                Arguments.of("GET", layout), Arguments.of("GET", layout + "/svg"),
                Arguments.of("GET", layout + "/zones/" + UUID.randomUUID()),
                Arguments.of("GET", layout + "/zones.csv"), Arguments.of("GET", layout + "/seats.csv"),
                Arguments.of("POST", layout + "/publish"),
                Arguments.of("POST", ADMIN + "/events/" + UUID.randomUUID() + "/showtimes"),
                Arguments.of("PATCH", ADMIN + "/showtimes/" + UUID.randomUUID()));
    }

    @ParameterizedTest
    @MethodSource("layoutOperations")
    void layoutOperations_return401WithoutToken(String method, String path) throws Exception {
        assertCode(request(method, path, null, null, 401), "UNAUTHORIZED");
    }

    @ParameterizedTest
    @MethodSource("layoutOperations")
    void layoutOperations_return403ForBuyer(String method, String path) throws Exception {
        assertCode(request(method, path, tokenFor(Role.BUYER), null, 403), "FORBIDDEN");
    }

    private Map<String, Object> showtimeBody(UUID hall, UUID layout, Instant now) {
        OffsetDateTime start = now.atOffset(ZoneOffset.UTC);
        return Map.of("hall_id", hall, "seat_layout_id", layout,
                "sale_opens_at", start.plusDays(2).toString(), "sale_closes_at", start.plusDays(6).toString(),
                "starts_at", start.plusDays(7).toString(), "ends_at", start.plusDays(7).plusHours(2).toString());
    }

    private UUID importSvg(String token, UUID hall, String svg) throws Exception {
        String boundary = "CP7-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"layout.svg\"\r\n"
                + "Content-Type: image/svg+xml\r\n\r\n" + svg + "\r\n--" + boundary + "--\r\n";
        return id(exchange("POST", ADMIN + "/halls/" + hall + "/seat-layouts/import", token,
                body, "multipart/form-data; boundary=" + boundary, 201));
    }

    private String request(String method, String path, String token, Map<String, ?> body, int status)
            throws Exception {
        return exchange(method, path, token, body == null ? "" : json.writeValueAsString(body),
                "application/json", status);
    }

    private String exchange(String method, String path, String token, String body, String contentType, int status)
            throws IOException, InterruptedException {
        String requestId = "cp7-" + UUID.randomUUID();
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType).header("X-Request-Id", requestId)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId);
        if (status >= 400) {
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
        } else if (path.endsWith("/svg")) {
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("image/svg+xml");
        } else if (path.endsWith(".csv")) {
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/csv");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment");
        }
        return response.body();
    }

    private void assertInventory(UUID showtime, UUID layout, UUID standingZone, int standingCapacity) {
        assertThat(jdbc.sql("SELECT seat_layout_id FROM showtime WHERE id = :id")
                .param("id", showtime).query(UUID.class).single()).isEqualTo(layout);
        var expectedSeats = jdbc.sql("""
                SELECT s.id FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = :id
                """).param("id", layout).query(UUID.class).list();
        assertThat(expectedSeats).hasSize(6699);
        assertThat(jdbc.sql("SELECT seat_id FROM showtime_seat WHERE showtime_id = :id AND status = 'AVAILABLE'")
                .param("id", showtime).query(UUID.class).list()).containsExactlyInAnyOrderElementsOf(expectedSeats);
        var expectedStock = jdbc.sql("""
                SELECT id, capacity FROM zone WHERE seat_layout_id = :id AND kind = 'STANDING'
                """).param("id", layout)
                .query((rs, row) -> new Stock(rs.getObject("id", UUID.class), rs.getInt("capacity"), 0, 0)).list();
        assertThat(expectedStock).hasSize(5).contains(new Stock(standingZone, standingCapacity, 0, 0));
        assertThat(jdbc.sql("""
                SELECT zone_id, capacity, sold, held FROM showtime_zone_stock WHERE showtime_id = :id
                """).param("id", showtime).query((rs, row) -> new Stock(rs.getObject("zone_id", UUID.class),
                rs.getInt("capacity"), rs.getInt("sold"), rs.getInt("held"))).list())
                .containsExactlyInAnyOrderElementsOf(expectedStock);
    }

    private void assertCsvMatchesStoredLayout(UUID layout, String zones, String seats) {
        assertThat(zones.lines().findFirst()).contains("zone_code,label,stand_code,level,kind,capacity,svg_id");
        assertThat(zones.lines().skip(1).toList()).hasSize(27).containsExactlyInAnyOrderElementsOf(
                jdbc.sql("""
                        SELECT code, name, stand_code, level, kind, capacity, svg_id
                        FROM zone WHERE seat_layout_id = :id
                        """).param("id", layout).query((rs, row) -> String.join(",", rs.getString("code"),
                        rs.getString("name"), rs.getString("stand_code"), rs.getString("level"),
                        rs.getString("kind"), rs.getString("capacity"), rs.getString("svg_id"))).list());
        assertThat(seats.lines().findFirst()).contains("zone_code,row_label,seat_number,x,y");
        assertThat(seats.lines().skip(1).toList()).hasSize(6699).containsExactlyInAnyOrderElementsOf(
                jdbc.sql("""
                        SELECT z.code, s.row_label, s.number, s.x, s.y
                        FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = :id
                        """).param("id", layout).query((rs, row) -> String.join(",", rs.getString("code"),
                        rs.getString("row_label"), rs.getString("number"), rs.getBigDecimal("x").toPlainString(),
                        rs.getBigDecimal("y").toPlainString())).list());
    }

    private void writeHandoffSamples(Map<String, String> samples) throws IOException {
        Path folder = Path.of("target/cp7-handoff");
        Files.createDirectories(folder);
        for (var sample : samples.entrySet()) {
            Files.writeString(folder.resolve(sample.getKey()), sample.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static UUID id(String body) {
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static UUID zoneId(String overview, String code) {
        return UUID.fromString(JsonPath.<List<String>>read(overview,
                "$.zones[?(@.code == '" + code + "')].id").getFirst());
    }

    private static void assertCode(String body, String code) {
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo(code);
    }

    private record Stock(UUID zoneId, int capacity, int sold, int held) {}
}
