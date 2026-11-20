package vingo.catalog.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vingo.shared.security.Role;
import vingo.catalog.VenueService;
import vingo.search.SearchService;
import tools.jackson.databind.json.JsonMapper;
import vingo.support.IntegrationTest;

class AdminSeatLayoutImportIntegrationTest extends IntegrationTest {

    private final PlatformTransactionManager transactionManager;
    private final VenueService venues;
    private final SearchService search;
    private final JsonMapper json;
    private final int port;

    @Autowired
    AdminSeatLayoutImportIntegrationTest(PlatformTransactionManager transactionManager, VenueService venues,
            SearchService search, JsonMapper json, @Value("${local.server.port}") int port) {
        this.transactionManager = transactionManager;
        this.venues = venues;
        this.search = search;
        this.json = json;
        this.port = port;
    }

    private static final String IMPORT =
            "/api/v1/admin/venue-imports/{hall_id}/files";
    private static final String LIST_LAYOUTS =
            "/api/v1/admin/venues/{hall_id}/seat-layouts";
    private static final String PUBLISH =
            "/api/v1/admin/seat-layouts/{layout_id}/publish";
    private static final String CSV =
            "/api/v1/admin/seat-layouts/{layout_id}/{filename}";
    private static final String OVERVIEW =
            "/api/v1/admin/seat-layouts/{layout_id}";
    private static final String SVG =
            "/api/v1/admin/seat-layouts/{layout_id}/svg";
    private static final String ZONE =
            "/api/v1/admin/seat-layouts/{layout_id}/zones/{zone_id}";
    private static final String CREATE_SHOWTIME =
            "/api/v1/admin/events/{event_id}/showtimes";
    private static final String UPDATE_SHOWTIME =
            "/api/v1/admin/showtimes/{showtime_id}";

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void importSvg_createsDraftAndExportsBothCsvFiles() {
        UUID hallId = catalog.event().venueId();
        String token = tokenFor(Role.ADMIN);

        UUID layoutId = importThienTruong(token, hallId);

        int savedCount = jdbc.sql("""
                SELECT count(*) FROM seat_map WHERE id = :id AND venue_id = :hallId
                """)
                .param("id", layoutId)
                .param("hallId", hallId)
                .query(Integer.class)
                .single();
        assertThat(savedCount).isOne();

        String zones = download(token, layoutId, "zones.csv");
        assertThat(zones.lines().count()).isEqualTo(3L);
        assertThat(zones).contains("B11-T2,B11-T2,").contains(",STANDING,1000,");

        String seats = download(token, layoutId, "seats.csv");
        assertThat(seats.lines().count()).isEqualTo(5L);
        assertThat(seats).contains("B3-T1,A,1,");
        assertThat(seats).doesNotContain("B11-T2,");
    }

    @ParameterizedTest
    @CsvSource({"false,ZONE_OVERLAPS_GATE,ZONE__A__STANDING__10", "true,SEAT_OVERLAPS_GATE,SEAT__A__R__1"})
    void importSvg_returns422WithCollisionIdsAndDoesNotSaveLayout(boolean seated, String reason, String layerId) {
        UUID hall = catalog.event().venueId();
        String zone = seated ? """
                <rect id="ZONE__A__SEATED" width="20" height="20"/>
                <g id="CONTENT__A"><circle id="SEAT__A__R__1" cx="3.5" cy="5" r="1"/>
                <path id="PITCH_SIDE__A" d="M0 0H20"/></g>
                """ : "<rect id='ZONE__A__STANDING__10' width='20' height='20'/>";
        byte[] input = ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>" + zone
                + "<rect id='GATE__G1' x='4' y='4' width='2' height='2'/></svg>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long before = jdbc.sql("SELECT count(*) FROM seat_map WHERE venue_id = :id")
                .param("id", hall).query(Long.class).single();
        uploadSvg(tokenFor(Role.ADMIN), hall, input, "image/svg+xml").expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_SEAT_LAYOUT")
                .jsonPath("$.issues[0].reason").isEqualTo(reason)
                .jsonPath("$.issues[0].layer_id").isEqualTo(layerId)
                .jsonPath("$.issues[0].gate_id").isEqualTo("GATE__G1")
                .jsonPath("$.issues[0].zone_id").exists()
                .jsonPath("$.issues[0].intersection_area").isNumber();
        assertThat(jdbc.sql("SELECT count(*) FROM seat_map WHERE venue_id = :id")
                .param("id", hall).query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void previewLayout_returnsOverviewSvgAndZoneDetails() {
        UUID hallId = catalog.event().venueId();
        String token = tokenFor(Role.ADMIN);
        UUID layoutId = importThienTruong(token, hallId);

        String overview = client.get().uri(OVERVIEW, layoutId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<String>read(overview, "$.id")).isEqualTo(layoutId.toString());
        assertThat(JsonPath.<Integer>read(overview, "$.zone_count")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(overview, "$.seat_count")).isEqualTo(4);
        assertThat(new BigDecimal(JsonPath.read(overview, "$.view_box.width").toString()))
                .isEqualByComparingTo("1600");
        assertThat(new BigDecimal(JsonPath.read(overview, "$.view_box.height").toString()))
                .isEqualByComparingTo("1200");
        assertThat(JsonPath.<List<?>>read(overview, "$.zones")).hasSize(2);

        UUID seatedZoneId = UUID.fromString(
                JsonPath.<List<String>>read(overview, "$.zones[?(@.code == 'B3-T1')].id").getFirst()
        );
        UUID standingZoneId = UUID.fromString(
                JsonPath.<List<String>>read(overview, "$.zones[?(@.code == 'B11-T2')].id").getFirst()
        );

        String svg = client.get().uri(SVG, layoutId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("image/svg+xml"))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(svg).contains("<svg").contains("ZONE__B3-T1__SEATED");

        String seatedDetail = client.get().uri(ZONE, layoutId, seatedZoneId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<String>read(seatedDetail, "$.code")).isEqualTo("B3-T1");
        assertThat(JsonPath.<String>read(seatedDetail, "$.kind")).isEqualTo("SEATED");
        assertThat(JsonPath.<List<Integer>>read(
                seatedDetail, "$.rows[?(@.row_label == 'A')].seats[*].number"
        )).containsExactlyInAnyOrder(1, 2);

        String standingDetail = client.get().uri(ZONE, layoutId, standingZoneId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<String>read(standingDetail, "$.code")).isEqualTo("B11-T2");
        assertThat(JsonPath.<String>read(standingDetail, "$.kind")).isEqualTo("STANDING");
        assertThat(JsonPath.<Integer>read(standingDetail, "$.capacity")).isEqualTo(1000);
        assertThat(standingDetail).doesNotContain("\"rows\"");

        client.get().uri(ZONE, layoutId, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createShowtime_returns400ForMissingFieldsAndInvalidTimeWindows() {
        var event = catalog.event();
        String token = tokenFor(Role.ADMIN);
        var body = showtimeBody(event.venueId(), event.layoutId());
        var missing = new HashMap<>(body);
        missing.remove("seat_layout_id");
        var invalidEnd = new HashMap<>(body);
        invalidEnd.put("ends_at", body.get("starts_at"));
        var invalidSale = new HashMap<>(body);
        invalidSale.put("sale_closes_at", body.get("sale_opens_at"));
        for (var request : List.of(missing, invalidEnd, invalidSale)) {
            client.post().uri(CREATE_SHOWTIME, event.id())
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void createShowtime_returns404ForMissingEventHallLayoutAndDeletedVenue() {
        var event = catalog.event();
        String token = tokenFor(Role.ADMIN);
        var missingHall = showtimeBody(event.venueId(), UUID.randomUUID());
        var missingLayout = showtimeBody(event.venueId(), UUID.randomUUID());
        for (var request : List.of(missingHall, missingLayout)) {
            client.post().uri(CREATE_SHOWTIME, event.id())
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange().expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        }
        var valid = showtimeBody(event.venueId(), event.layoutId());
        client.post().uri(CREATE_SHOWTIME, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(valid)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        client.delete().uri("/api/v1/admin/venues/{id}", event.venueId())
                .headers(headers -> headers.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
        client.post().uri(CREATE_SHOWTIME, event.id())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(valid)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void updateShowtime_returns400ForInvalidTimeAnd404ForMissingReferences() {
        var event = catalog.event();
        String token = tokenFor(Role.ADMIN);
        UUID showtime = createShowtime(token, event.id(), showtimeBody(event.venueId(), event.layoutId()));
        var body = showtimeBody(event.venueId(), event.layoutId());
        client.patch().uri(UPDATE_SHOWTIME, showtime)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ends_at", body.get("sale_opens_at")))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        client.patch().uri(UPDATE_SHOWTIME, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        client.patch().uri(UPDATE_SHOWTIME, showtime)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("seat_layout_id", UUID.randomUUID()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        assertInventory(showtime, event.layoutId());
    }

    private Map<String, Object> showtimeBody(UUID hallId, UUID layoutId) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        return Map.of(
                        "seat_layout_id", layoutId,
                "sale_opens_at", now.plusDays(2).toString(),
                "sale_closes_at", now.plusDays(6).toString(),
                "starts_at", now.plusDays(7).toString(), "ends_at", now.plusDays(7).plusHours(2).toString());
    }

    @Test
    void createShowtime_waitsForVenueDeletionAndReturns404AfterCommit() throws Exception {
        var event = catalog.event();
        String token = tokenFor(Role.ADMIN);
        var body = showtimeBody(event.venueId(), event.layoutId());
        var executor = Executors.newSingleThreadExecutor();
        var startRequest = new CountDownLatch(1);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var httpRequest = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/admin/events/" + event.id() + "/showtimes"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        var request = executor.submit(() -> {
            if (!startRequest.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Giao dịch xóa chưa sẵn sàng.");
            }
            var response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("NOT_FOUND");
            return null;
        });
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                venues.delete(event.venueId());
                startRequest.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (!request.isDone() && System.nanoTime() < deadline) {
                    jdbc.sql("SELECT pg_stat_clear_snapshot()").query((rs, row) -> true).single();
                    waiting = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE wait_event_type = 'Lock'
                                  AND query LIKE '%FOR UPDATE OF v%'
                            )
                            """).query(Boolean.class).single();
                    if (waiting) {
                        break;
                    }
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
                assertThat(waiting).as("Create must wait for the venue deletion transaction").isTrue();
            });
            request.get(10, TimeUnit.SECONDS);
        } finally {
            request.cancel(true);
            executor.shutdownNow();
            http.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM showtime WHERE event_id = :id")
                .param("id", event.id()).query(Integer.class).single()).isZero();
    }

    private UUID createShowtime(String token, UUID eventId, Map<String, Object> body) {
        String response = client.post().uri(CREATE_SHOWTIME, eventId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange().expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private void assertInventory(UUID showtimeId, UUID layoutId) {
        var expectedSeats = jdbc.sql("""
                SELECT s.id FROM seat s JOIN zone z ON z.id = s.zone_id
                WHERE z.seat_map_id = :layout AND z.kind = 'SEATED'
                """).param("layout", layoutId).query(UUID.class).list();
        var actualSeats = jdbc.sql("SELECT seat_id FROM seat_inventory WHERE showtime_id = :id")
                .param("id", showtimeId).query(UUID.class).list();
        assertThat(actualSeats).containsExactlyInAnyOrderElementsOf(expectedSeats);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM seat_inventory WHERE showtime_id = :id AND status <> 'AVAILABLE'
                """).param("id", showtimeId).query(Integer.class).single()).isZero();
        var expectedStocks = jdbc.sql("""
                SELECT id, capacity FROM zone WHERE seat_map_id = :layout AND kind = 'STANDING'
                """).param("layout", layoutId)
                .query((rs, row) -> new StandingStock(rs.getObject("id", UUID.class), rs.getInt("capacity"), 0, 0))
                .list();
        var actualStocks = jdbc.sql("""
                SELECT zone_id, capacity, sold, held FROM standing_inventory WHERE showtime_id = :id
                """).param("id", showtimeId)
                .query((rs, row) -> new StandingStock(rs.getObject("zone_id", UUID.class),
                        rs.getInt("capacity"), rs.getInt("sold"), rs.getInt("held"))).list();
        assertThat(actualStocks).containsExactlyInAnyOrderElementsOf(expectedStocks);
    }

    private record StandingStock(UUID zoneId, int capacity, int sold, int held) {}

    @Test
    void importSvg_returns400ForEmptyFile() {
        uploadSvg(tokenFor(Role.ADMIN), catalog.event().venueId(), new byte[0], "image/svg+xml")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void importSvg_returns404ForMissingVenue() {
        client.post().uri("/api/v1/admin/venue-imports").headers(h->h.setBearerAuth(tokenFor(Role.ADMIN))).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("target_venue_id",UUID.randomUUID())).exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void importSvg_returns415ForUnsupportedOrMalformedMediaType() throws Exception {
        String token = tokenFor(Role.ADMIN);
        UUID hallId = catalog.event().venueId();
        for (String type : List.of("text/plain", "invalid-content-type")) {
            var response = rawUploadSvg(token, hallId, standingSvg(), type);
            assertThat(response.statusCode()).isEqualTo(415);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }
    }

    @Test
    void importSvg_returns422ForInvalidXml() {
        String token = tokenFor(Role.ADMIN);
        String state = vingo.support.VenueImportHttpFixture.create(client, token, catalog.event().venueId());
        vingo.support.VenueImportHttpFixture.uploadResponse(client, token, vingo.support.VenueImportHttpFixture.id(state),
                vingo.support.VenueImportHttpFixture.revision(state),
                "<svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/svg+xml")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_SEAT_LAYOUT");
    }

    @Test
    void importSvg_returns413WhenFileExceedsConfiguredLimit() throws Exception {
        var response = rawUploadSvg(tokenFor(Role.ADMIN), catalog.event().venueId(),
                new byte[10 * 1024 * 1024 + 1], "image/svg+xml");
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    private HttpResponse<String> rawUploadSvg(String token, UUID hallId, byte[] input, String contentType)
            throws Exception {
        UUID session=vingo.support.VenueImportHttpFixture.id(vingo.support.VenueImportHttpFixture.create(client,token,hallId));
        String boundary = "svg-test-boundary";
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"revision\"\r\n\r\n0\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"layout.svg\""
                + "\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(input);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/admin/venue-imports/" + session + "/files"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec uploadSvg(
            String token, UUID venueId, byte[] input, String contentType) {
        String state=vingo.support.VenueImportHttpFixture.create(client,token,venueId);
        UUID id=vingo.support.VenueImportHttpFixture.id(state);
        long rev=vingo.support.VenueImportHttpFixture.revision(state);
        if(input.length==0 || !"image/svg+xml".equals(contentType)) return vingo.support.VenueImportHttpFixture.uploadResponse(client,token,id,rev,input,contentType);
        String[] layers;
        try {layers=vingo.support.VenueImportHttpFixture.split(new String(input,java.nio.charset.StandardCharsets.UTF_8));}
        catch(IllegalStateException ex) {layers=new String[]{"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><rect id='GATE__G1' x='90' y='90' width='2' height='2'/></svg>",new String(input,java.nio.charset.StandardCharsets.UTF_8)};}
        state=vingo.support.VenueImportHttpFixture.upload(client,token,id,rev,layers[0].getBytes(java.nio.charset.StandardCharsets.UTF_8),contentType);
        state=vingo.support.VenueImportHttpFixture.upload(client,token,id,vingo.support.VenueImportHttpFixture.revision(state),layers[1].getBytes(java.nio.charset.StandardCharsets.UTF_8),contentType);
        state=client.patch().uri("/api/v1/admin/venue-imports/{id}",id).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("revision",vingo.support.VenueImportHttpFixture.revision(state),"draft_data",Map.of("layout_name","CP6","layout_type","FOOTBALL")))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return client.post().uri("/api/v1/admin/venue-imports/{id}/complete",id).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("revision",vingo.support.VenueImportHttpFixture.revision(state))).exchange();
    }

    private static byte[] standingSvg() {
        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                  <rect id="ZONE__A-T1__STANDING__10" width="50" height="50"/>
                </svg>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @CsvSource({"2,4,6,SCHEDULED", "-2,2,4,ON_SALE", "-4,-2,2,SALES_CLOSED", "-6,-4,-2,FINISHED"})
    void createShowtime_returnsComputedStatusAndStoresScheduled(
            int opensHours, int closesHours, int endsHours, String expected) {
        var event = catalog.event();
        String token = tokenFor(Role.ADMIN);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        var body = Map.<String, Object>of(
                        "seat_layout_id", event.layoutId(),
                "sale_opens_at", now.plusHours(opensHours).toString(),
                "sale_closes_at", now.plusHours(closesHours).toString(),
                "starts_at", now.plusHours(endsHours - 1).toString(),
                "ends_at", now.plusHours(endsHours).toString());
        String response = client.post().uri(CREATE_SHOWTIME, event.id())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange().expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(response, "$.status")).isEqualTo(expected);
        UUID id = UUID.fromString(JsonPath.read(response, "$.id"));
        assertThat(jdbc.sql("SELECT cancelled_at FROM showtime WHERE id = :id")
                .param("id", id).query(OffsetDateTime.class).optional()).isEmpty();
    }

    private UUID importThienTruong(String token, UUID venueId) {
        return vingo.support.VenueImportHttpFixture.importLayout(client,token,venueId,"Bóng đá",vingo.support.VenueImportHttpFixture.SAMPLE_DETAIL);
    }

    private String download(String token, UUID layoutId, String filename) {
        return client.get().uri(CSV, layoutId, filename)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.parseMediaType("text/csv"))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }
}
