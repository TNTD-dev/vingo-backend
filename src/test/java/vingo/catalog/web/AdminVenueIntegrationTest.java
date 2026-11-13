package vingo.catalog.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Window;
import vingo.support.IntegrationTest;

class AdminVenueIntegrationTest extends IntegrationTest {

    private static final String VENUES = "/api/v1/admin/venues";
    private static final String VENUE = VENUES + "/{id}";
    private static final String ADDRESS = "Địa chỉ sân test";

    private String adminToken;

    @BeforeEach
    void authenticateAdmin() {
        adminToken = tokenFor(Role.ADMIN);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void createVenue_returns201AndPersistsVenue() {
        String name = uniqueName();
        UUID id = createVenue(name, "HN");

        String savedName = jdbc.sql("SELECT name FROM venue WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();

        assertThat(savedName).isEqualTo(name);
    }

    @Test
    void createVenue_returns400_whenAddressIsMissing() {
        client.post().uri(VENUES)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", uniqueName(), "type", "STADIUM", "city", "HN"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createVenue_returns400_whenNameIsBlank() {
        client.post().uri(VENUES)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "   ", "type", "STADIUM",
                        "address", ADDRESS, "city", "HN"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listVenues_filtersByNameAndCity_andReturnsNextPage() {
        String prefix = uniqueName();
        UUID first = createVenue(prefix + " A", "HN");
        UUID second = createVenue(prefix + " B", "HN");
        postHall(first, "Hall A").expectStatus().isCreated();
        postHall(second, "Hall B").expectStatus().isCreated();
        createVenue(prefix + " ngoài bộ lọc", "DN");
        createVenue(uniqueName(), "HN");

        String pageOne = client.get()
                .uri(builder -> builder.path(VENUES)
                        .queryParam("q", prefix)
                        .queryParam("city", "HN")
                        .queryParam("limit", 1)
                        .build())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String firstId = JsonPath.read(pageOne, "$.data[0].id");
        String cursor = JsonPath.read(pageOne, "$.next_cursor");
        assertThat(cursor).isNotBlank();

        String pageTwo = client.get()
                .uri(builder -> builder.path(VENUES)
                        .queryParam("q", prefix)
                        .queryParam("city", "HN")
                        .queryParam("limit", 1)
                        .queryParam("cursor", cursor)
                        .build())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String secondId = JsonPath.read(pageTwo, "$.data[0].id");
        String nextCursor = JsonPath.read(pageTwo, "$.next_cursor");

        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(new String[] {firstId, secondId})
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(nextCursor).isNull();
        var hallNames = Map.of(first.toString(), "Hall A", second.toString(), "Hall B");
        assertThat(JsonPath.<List<String>>read(pageOne, "$.data[0].halls[*].name"))
                .containsExactly(hallNames.get(firstId));
        assertThat(JsonPath.<List<String>>read(pageTwo, "$.data[0].halls[*].name"))
                .containsExactly(hallNames.get(secondId));
    }

    @Test
    void listVenues_returnsOnlyOwnHallsAndEmptyListForVenueWithoutHall() {
        String prefix = uniqueName();
        UUID first = createVenue(prefix + " A", "HN");
        UUID second = createVenue(prefix + " B", "HN");
        UUID empty = createVenue(prefix + " C", "HN");
        postHall(first, "Sân chính").expectStatus().isCreated();
        postHall(first, "Khán đài phụ").expectStatus().isCreated();
        postHall(second, "Hall B").expectStatus().isCreated();
        String response = client.get().uri(builder -> builder.path(VENUES)
                        .queryParam("q", prefix).queryParam("limit", 100).build())
                .headers(h -> h.setBearerAuth(adminToken)).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        String firstPath = "$.data[?(@.id == '" + first + "')].halls";
        String secondPath = "$.data[?(@.id == '" + second + "')].halls";
        assertThat(JsonPath.<List<String>>read(response, firstPath + "[*].name"))
                .containsExactlyInAnyOrder("Sân chính", "Khán đài phụ");
        assertThat(JsonPath.<List<String>>read(response, secondPath + "[*].name")).containsExactly("Hall B");
        assertThat(JsonPath.<List<String>>read(response, firstPath + "[*].venue_id")).containsOnly(first.toString());
        assertThat(JsonPath.<List<String>>read(response, secondPath + "[*].venue_id")).containsOnly(second.toString());
        assertThat(JsonPath.<List<List<Object>>>read(response,
                "$.data[?(@.id == '" + empty + "')].halls")).containsExactly(List.of());
    }

    @Test
    void listVenues_returnsEmptyPageWithoutQueryingEmptyHallIds() {
        client.get().uri(builder -> builder.path(VENUES).queryParam("q", uniqueName()).build())
                .headers(h -> h.setBearerAuth(adminToken)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data").isEmpty();
    }

    @Test
    void listVenues_returns400_whenCursorIsInvalid() {
        client.get()
                .uri(builder -> builder.path(VENUES).queryParam("cursor", "***").build())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }
    
    @Test
    void listVenues_returns200_whenVenueHasLegacyCityCode() {
        String name = uniqueName();

        jdbc.sql("""
                INSERT INTO venue (name, type, address, city)
                VALUES (:name, 'STADIUM', :address, 'LEGACY-CITY')
                """)
                .param("name", name)
                .param("address", ADDRESS)
                .update();

        client.get()
                .uri(builder -> builder.path(VENUES).queryParam("q", name).build())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].name").isEqualTo(name)
                .jsonPath("$.data[0].city_name").isEqualTo("LEGACY-CITY");
    }

    @Test
    void updateVenue_changesOnlyFieldsSentInPatch() {
        UUID id = createVenue(uniqueName(), "HN");
        String newName = uniqueName();

        client.patch().uri(VENUE, id)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", newName))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.name").isEqualTo(newName)
                .jsonPath("$.address").isEqualTo(ADDRESS)
                .jsonPath("$.type").isEqualTo("STADIUM")
                .jsonPath("$.city").isEqualTo("HN");
    }

    @Test
    void updateVenue_returns404_whenVenueDoesNotExist() {
        client.patch().uri(VENUE, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Tên mới"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void updateVenue_returns400_whenNameIsBlank() {
        UUID id = createVenue(uniqueName(), "HN");
        client.patch().uri(VENUE, id)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "   "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void deleteVenue_returns404_whenVenueDoesNotExist() {
        client.delete().uri(VENUE, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void createVenue_returnsDisplayNameForEachSupportedCity() {
        Map<String, String> cities = Map.of("HN", "Hà Nội", "HCM", "TP. Hồ Chí Minh", "DN", "Đà Nẵng");
        for (var city : cities.entrySet()) {
            client.post().uri(VENUES)
                    .headers(headers -> headers.setBearerAuth(adminToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", uniqueName(), "type", "STADIUM",
                            "address", ADDRESS, "city", city.getKey()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.city").isEqualTo(city.getKey())
                    .jsonPath("$.city_name").isEqualTo(city.getValue());
        }
    }

    @Test
    void deleteVenue_softDeletesAndRemovesVenueFromList() {
        String name = uniqueName();
        UUID id = createVenue(name, "HN");

        client.delete().uri(VENUE, id)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();

        boolean softDeleted = jdbc.sql("""
                SELECT deleted_at IS NOT NULL
                FROM venue
                WHERE id = :id
                """)
                .param("id", id)
                .query(Boolean.class)
                .single();

        assertThat(softDeleted).isTrue();

        client.get()
                .uri(builder -> builder.path(VENUES).queryParam("q", name).build())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void deleteVenue_returns409AfterSalesClose_and204AtShowtimeEnd() {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");
        clock.set(now);

        var event = catalog.event();
        catalog.showtime(event, new Window(
                "SCHEDULED",
                Duration.ofHours(-3),  // mở bán
                Duration.ofHours(-1),  // đóng bán
                Duration.ofMinutes(-30),
                Duration.ofMinutes(30)
        ));

        client.delete().uri(VENUE, event.venueId())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("VENUE_IN_USE");

        clock.set(now.plus(Duration.ofMinutes(30)));

        client.delete().uri(VENUE, event.venueId())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void listVenues_returns401_whenTokenIsMissing() {
        client.get().uri(VENUES)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void listVenues_returns403_whenUserIsBuyer() {
        client.get().uri(VENUES)
                .headers(headers -> headers.setBearerAuth(tokenFor(Role.BUYER)))
                .exchange()
                .expectStatus().isForbidden();
    }

    private UUID createVenue(String name, String city) {
        String json = client.post().uri(VENUES)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "type", "STADIUM",
                        "address", ADDRESS, "city", city))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String id = JsonPath.read(json, "$.id");
        return UUID.fromString(id);
    }

    private static String uniqueName() {
        return "CP3-" + UUID.randomUUID();
    }

    @ParameterizedTest
    @CsvSource({"DRAFT,1,409", "DRAFT,0,204", "DRAFT,-1,204", "SCHEDULED,1,409", "CANCELLED,1,204"})
    void deleteVenue_checksUnfinishedDraftAndScheduledShowtimes(String status, int endsHours, int expected) {
        Instant now = Instant.parse("2026-11-12T05:00:00Z");
        clock.set(now);
        var event = catalog.event();
        catalog.showtime(event, new Window(status, Duration.ofHours(-4), Duration.ofHours(-3),
                Duration.ofHours(-2), Duration.ofHours(endsHours)));
        var response = client.delete().uri(VENUE, event.venueId())
                .headers(headers -> headers.setBearerAuth(adminToken)).exchange()
                .expectStatus().isEqualTo(expected);
        if (expected == 409) {
            response.expectBody().jsonPath("$.code").isEqualTo("VENUE_IN_USE");
        }
    }

    static Stream<Arguments> protectedVenueOperations() {
        String venue = VENUES + "/" + UUID.randomUUID();
        return Stream.of(Arguments.of(HttpMethod.POST, VENUES),
                Arguments.of(HttpMethod.PATCH, venue),
                Arguments.of(HttpMethod.DELETE, venue),
                Arguments.of(HttpMethod.POST, venue + "/halls"));
    }

    @ParameterizedTest
    @MethodSource("protectedVenueOperations")
    void venueWrites_return401WithoutToken(HttpMethod method, String path) {
        client.method(method).uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Sân test", "type", "STADIUM", "address", ADDRESS, "city", "HN"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @ParameterizedTest
    @MethodSource("protectedVenueOperations")
    void venueWrites_return403ForBuyer(HttpMethod method, String path) {
        client.method(method).uri(path)
                .headers(headers -> headers.setBearerAuth(tokenFor(Role.BUYER)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Sân test", "type", "STADIUM", "address", ADDRESS, "city", "HN"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    private static final String HALLS = VENUE + "/halls";

    private org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec
            postHall(UUID venueId, String name) {
        return client.post().uri(HALLS, venueId)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .exchange();
    }
    @Test
    void createHall_returns201ForActiveVenue() {
        UUID venueId = createVenue(uniqueName(), "HN");

        postHall(venueId, " Sân chính ")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.venue_id").isEqualTo(venueId.toString())
                .jsonPath("$.name").isEqualTo("Sân chính");
    }

    @Test
    void createHall_returns409WhenNameAlreadyExistsInSameVenue() {
        UUID venueId = createVenue(uniqueName(), "HN");
        postHall(venueId, "Sân chính").expectStatus().isCreated();

        postHall(venueId, "Sân chính")
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("HALL_NAME_TAKEN");
    }

    @Test
    void createHall_returns400WhenNameIsBlank() {
        UUID venueId = createVenue(uniqueName(), "HN");

        postHall(venueId, "   ")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createHall_returns404ForDeactivatedVenue() {
        UUID venueId = createVenue(uniqueName(), "HN");

        client.delete().uri(VENUE, venueId)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();

        postHall(venueId, "Sân chính")
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }
}
