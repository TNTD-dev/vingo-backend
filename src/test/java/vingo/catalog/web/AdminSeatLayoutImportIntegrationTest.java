package vingo.catalog.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;

import com.jayway.jsonpath.JsonPath;

import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class AdminSeatLayoutImportIntegrationTest extends IntegrationTest {

    private static final String IMPORT =
            "/api/v1/admin/halls/{hall_id}/seat-layouts/import";
    private static final String LIST_LAYOUTS =
            "/api/v1/admin/halls/{hall_id}/seat-layouts";
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
        UUID hallId = catalog.event().hallId();
        String token = tokenFor(Role.ADMIN);

        UUID layoutId = importThienTruong(token, hallId);

        String savedStatus = jdbc.sql("""
                SELECT status FROM seat_layout WHERE id = :id AND hall_id = :hallId
                """)
                .param("id", layoutId)
                .param("hallId", hallId)
                .query(String.class)
                .single();
        assertThat(savedStatus).isEqualTo("DRAFT");

        String zones = download(token, layoutId, "zones.csv");
        assertThat(zones.lines().count()).isEqualTo(28L);
        assertThat(zones).contains("B11-T2,B11-T2,B,2,STANDING,1000,");

        String seats = download(token, layoutId, "seats.csv");
        assertThat(seats.lines().count()).isEqualTo(6700L);
        assertThat(seats).contains("B3-T1,A,1,532.77,349.88");
        assertThat(seats).doesNotContain("B11-T2,");
    }

    @Test
    void previewLayout_returnsOverviewSvgAndZoneDetails() {
        UUID hallId = catalog.event().hallId();
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
        assertThat(JsonPath.<String>read(overview, "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<Integer>read(overview, "$.zone_count")).isEqualTo(27);
        assertThat(JsonPath.<Integer>read(overview, "$.seat_count")).isEqualTo(6699);
        assertThat(new BigDecimal(JsonPath.read(overview, "$.view_box.width").toString()))
                .isEqualByComparingTo("1600");
        assertThat(new BigDecimal(JsonPath.read(overview, "$.view_box.height").toString()))
                .isEqualByComparingTo("1200");
        assertThat(JsonPath.<List<?>>read(overview, "$.zones")).hasSize(27);

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
        assertThat(JsonPath.<String>read(seatedDetail, "$.pitch_side_svg_id")).isEqualTo("PITCH_SIDE__B3-T1");
        assertThat(new BigDecimal(JsonPath.read(seatedDetail, "$.bounds.width").toString()))
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(new BigDecimal(
                JsonPath.<List<Object>>read(
                        seatedDetail, "$.rows[?(@.row_label == 'A')].seats[?(@.number == 1)].x"
                ).getFirst().toString()
        )).isEqualByComparingTo("532.77");
        assertThat(new BigDecimal(
                JsonPath.<List<Object>>read(
                        seatedDetail, "$.rows[?(@.row_label == 'A')].seats[?(@.number == 1)].y"
                ).getFirst().toString()
        )).isEqualByComparingTo("349.88");

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
    void publishAndListLayouts_isIdempotentAndPaginatesNewestFirst() {
        Instant t1 = Instant.parse("2026-11-12T04:00:00Z");
        clock.set(t1);
        String token = tokenFor(Role.ADMIN);

        UUID venueId = jdbc.sql("""
                INSERT INTO venue (name, type, address, city)
                VALUES ('Sân Thiên Trường', 'STADIUM', 'Nam Định', 'HN')
                RETURNING id
                """).query(UUID.class).single();
        UUID hallId = jdbc.sql("""
                INSERT INTO hall (venue_id, name) VALUES (:venueId, 'Khán đài chính') RETURNING id
                """).param("venueId", venueId).query(UUID.class).single();
        UUID otherHallId = catalog.event().hallId();

        UUID v1 = importThienTruong(token, hallId);
        UUID v2 = importThienTruong(token, hallId);

        String firstPublish = client.post().uri(PUBLISH, v1)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String publishedAt1 = JsonPath.read(firstPublish, "$.published_at");
        Number version1 = JsonPath.read(firstPublish, "$.version");
        assertThat(JsonPath.<String>read(firstPublish, "$.status")).isEqualTo("PUBLISHED");
        assertThat(OffsetDateTime.parse(publishedAt1)).isEqualTo(t1.atOffset(ZoneOffset.UTC));

        clock.set(t1.plus(Duration.ofMinutes(15)));
        String secondPublish = client.post().uri(PUBLISH, v1)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<Number>read(secondPublish, "$.version").longValue()).isEqualTo(version1.longValue());
        assertThat(OffsetDateTime.parse(JsonPath.read(secondPublish, "$.published_at")))
                .isEqualTo(OffsetDateTime.parse(publishedAt1));

        client.post().uri(PUBLISH, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound();

        String page1 = client.get()
                .uri(builder -> builder.path(LIST_LAYOUTS).queryParam("limit", 1).build(hallId))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<List<?>>read(page1, "$.data")).hasSize(1);
        assertThat(JsonPath.<String>read(page1, "$.data[0].id")).isEqualTo(v2.toString());
        assertThat(JsonPath.<String>read(page1, "$.data[0].status")).isEqualTo("DRAFT");
        String cursor = JsonPath.read(page1, "$.next_cursor");
        assertThat(cursor).isNotBlank();

        String page2 = client.get()
                .uri(builder -> builder.path(LIST_LAYOUTS)
                        .queryParam("limit", 1)
                        .queryParam("cursor", cursor)
                        .build(hallId))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<List<?>>read(page2, "$.data")).hasSize(1);
        assertThat(JsonPath.<String>read(page2, "$.data[0].id")).isEqualTo(v1.toString());
        assertThat(JsonPath.<String>read(page2, "$.data[0].status")).isEqualTo("PUBLISHED");
        assertThat(JsonPath.<String>read(page2, "$.next_cursor")).isNull();
        assertThat(page1 + page2).doesNotContain(otherHallId.toString());
    }

    @Test
    void showtimeAttachment_validatesPublishedHallAndPreservesOldVersionAcrossShowtimes() {
        Instant now = Instant.parse("2026-11-12T05:00:00Z");
        clock.set(now);
        String token = tokenFor(Role.ADMIN);

        var event = catalog.event();
        UUID hallId = event.hallId();
        UUID otherHallId = catalog.event().hallId();

        UUID draftLayoutId = importThienTruong(token, hallId);

        OffsetDateTime saleOpens = now.plus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC);
        OffsetDateTime saleCloses = now.plus(Duration.ofDays(6)).atOffset(ZoneOffset.UTC);
        OffsetDateTime startsAt = now.plus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC);
        OffsetDateTime endsAt = startsAt.plusHours(2);

        client.post().uri(CREATE_SHOWTIME, event.id())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "hall_id", hallId,
                        "seat_layout_id", draftLayoutId,
                        "sale_opens_at", saleOpens.toString(),
                        "sale_closes_at", saleCloses.toString(),
                        "starts_at", startsAt.toString(),
                        "ends_at", endsAt.toString()
                ))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_SEAT_LAYOUT");

        client.post().uri(PUBLISH, draftLayoutId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();
        UUID layoutV1 = draftLayoutId;

        client.post().uri(CREATE_SHOWTIME, event.id())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "hall_id", otherHallId,
                        "seat_layout_id", layoutV1,
                        "sale_opens_at", saleOpens.toString(),
                        "sale_closes_at", saleCloses.toString(),
                        "starts_at", startsAt.toString(),
                        "ends_at", endsAt.toString()
                ))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_SEAT_LAYOUT");

        String createdA = client.post().uri(CREATE_SHOWTIME, event.id())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "hall_id", hallId,
                        "seat_layout_id", layoutV1,
                        "sale_opens_at", saleOpens.toString(),
                        "sale_closes_at", saleCloses.toString(),
                        "starts_at", startsAt.toString(),
                        "ends_at", endsAt.toString()
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        UUID showtimeA = UUID.fromString(JsonPath.read(createdA, "$.id"));
        assertThat(JsonPath.<String>read(createdA, "$.seat_layout_id")).isEqualTo(layoutV1.toString());

        UUID layoutV2 = importThienTruong(token, hallId);
        client.post().uri(PUBLISH, layoutV2)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();

        String createdB = client.post().uri(CREATE_SHOWTIME, event.id())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "hall_id", hallId,
                        "seat_layout_id", layoutV2,
                        "sale_opens_at", saleOpens.plusDays(1).toString(),
                        "sale_closes_at", saleCloses.plusDays(1).toString(),
                        "starts_at", startsAt.plusDays(1).toString(),
                        "ends_at", endsAt.plusDays(1).toString()
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        UUID showtimeB = UUID.fromString(JsonPath.read(createdB, "$.id"));

        UUID savedLayoutA = jdbc.sql("SELECT seat_layout_id FROM showtime WHERE id = :id")
                .param("id", showtimeA).query(UUID.class).single();
        UUID savedLayoutB = jdbc.sql("SELECT seat_layout_id FROM showtime WHERE id = :id")
                .param("id", showtimeB).query(UUID.class).single();
        assertThat(savedLayoutA).isEqualTo(layoutV1);
        assertThat(savedLayoutB).isEqualTo(layoutV2);

        client.get().uri(OVERVIEW, layoutV1)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(layoutV1.toString())
                .jsonPath("$.status").isEqualTo("PUBLISHED");

        client.get().uri(OVERVIEW, layoutV2)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(layoutV2.toString())
                .jsonPath("$.status").isEqualTo("PUBLISHED");

        clock.set(saleOpens.toInstant());
        client.patch().uri(UPDATE_SHOWTIME, showtimeA)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("seat_layout_id", layoutV2))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SHOWTIME_LOCKED");
    }

    private UUID importThienTruong(String token, UUID hallId) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new FileSystemResource(
                "sources/LAYOUT__THIEN-TRUONG.svg"
        )).contentType(MediaType.parseMediaType("image/svg+xml"));

        String body = client.post().uri(IMPORT, hallId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts.build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<Integer>read(body, "$.zone_count")).isEqualTo(27);
        assertThat(JsonPath.<Integer>read(body, "$.seat_count")).isEqualTo(6699);
        return UUID.fromString(JsonPath.read(body, "$.id"));
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