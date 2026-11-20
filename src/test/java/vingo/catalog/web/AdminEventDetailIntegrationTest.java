package vingo.catalog.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vingo.shared.security.Role;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;
import vingo.support.IntegrationTest;

class AdminEventDetailIntegrationTest extends IntegrationTest {

    private static final String PATH =
            "/api/v1/admin/events/{event_id}";

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void getAdminEvent_returnsCreatedDraftWithSeparatePosters() {
        String token = tokenFor(Role.ADMIN);

        String created = client.post()
                .uri("/api/v1/admin/events")
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "title": "Sự kiện nháp",
                          "category": "FOOTBALL",
                          "description": "Thông tin thử nghiệm",
                          "home_team": "Đội A",
                          "poster_mobile_url": "https://example.com/mobile.jpg",
                          "poster_desktop_url": "https://example.com/desktop.jpg",
                          "max_tickets_per_order": 4
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));

        client.get().uri(PATH, id)
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.title").isEqualTo("Sự kiện nháp")
                .jsonPath("$.category").isEqualTo("FOOTBALL")
                .jsonPath("$.description")
                    .isEqualTo("Thông tin thử nghiệm")
                .jsonPath("$.home_team").isEqualTo("Đội A")
                .jsonPath("$.poster_mobile_url")
                    .isEqualTo("https://example.com/mobile.jpg")
                .jsonPath("$.poster_desktop_url")
                    .isEqualTo("https://example.com/desktop.jpg")
                .jsonPath("$.max_tickets_per_order").isEqualTo(4)
                .jsonPath("$.price_tiers").isEmpty()
                .jsonPath("$.showtimes").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DRAFT", "PUBLISHED", "CANCELLED"
    })
    void getAdminEvent_returnsEventRegardlessOfStatus(String status) {
        var event = catalog.event(
                EventSpec.defaults()
                        .withCity("HN")
                        .withStatus(status)
        );

        client.get().uri(PATH, event.id())
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(event.id().toString())
                .jsonPath("$.status").isEqualTo(status);
    }

    @Test
    void getAdminEvent_returnsTiersAndAllShowtimesWithTheirOwnVenues() {
        clock.set(Instant.parse("2026-11-12T08:00:00Z"));

        var firstEvent = catalog.event(
                EventSpec.defaults().withCity("HN").withStatus("DRAFT")
        );

        var draft = catalog.showtime(firstEvent, Window.notOpenYet());
        var cancelled = catalog.showtime(firstEvent, Window.cancelled());
        var otherVenue = catalog.showtime(
                firstEvent, Window.onSale()
        );

        String response = readDetails(firstEvent.id());

        assertThat(JsonPath.<List<String>>read(
                response, "$.price_tiers[*].id"))
                .containsExactlyInAnyOrder(
                        firstEvent.vipPriceTierId().toString(),
                        firstEvent.gaPriceTierId().toString()
                );

        assertThat(JsonPath.<List<String>>read(
                response, "$.showtimes[*].id"))
                .containsExactlyInAnyOrder(
                        draft.id().toString(),
                        cancelled.id().toString(),
                        otherVenue.id().toString()
                );

        String draftPath = "$.showtimes[?(@.id == '"
                + draft.id() + "')]";
        String otherPath = "$.showtimes[?(@.id == '"
                + otherVenue.id() + "')]";
        String cancelledPath = "$.showtimes[?(@.id == '"
                + cancelled.id() + "')]";

        assertThat(JsonPath.<List<String>>read(
                response, draftPath + ".status"))
                .containsExactly("DRAFT");
        assertThat(JsonPath.<List<String>>read(
                response, cancelledPath + ".status"))
                .containsExactly("CANCELLED");

        assertThat(JsonPath.<List<String>>read(
                response, draftPath + ".venue.id"))
                .containsExactly(firstEvent.venueId().toString());
        assertThat(JsonPath.<List<String>>read(
                response, draftPath + ".venue.city"))
                .containsExactly("HN");

        assertThat(JsonPath.<List<String>>read(
                response, otherPath + ".venue.id"))
                .containsExactly(firstEvent.venueId().toString());
        assertThat(JsonPath.<List<String>>read(
                response, otherPath + ".venue.city"))
                .containsExactly("HN");

        assertThat(JsonPath.<List<String>>read(
                response, otherPath + ".venue.id"))
                .containsExactly(firstEvent.venueId().toString());
        assertThat(JsonPath.<List<String>>read(
                response, otherPath + ".seat_layout_id"))
                .containsExactly(firstEvent.layoutId().toString());
    }

    @Test
    void getAdminEvent_computesShowtimeStatusWithoutChangingDatabase() {
        Instant start = Instant.parse("2026-11-12T08:00:00Z");
        clock.set(start);

        var event = catalog.event(
                EventSpec.defaults().withCity("HN")
        );

        var showtime = catalog.showtime(event, new Window(
                "SCHEDULED",
                Duration.ofHours(1),
                Duration.ofHours(2),
                Duration.ofHours(3),
                Duration.ofHours(4)
        ));

        assertShowtimeStatus(event.id(), "SCHEDULED");

        clock.set(start.plus(Duration.ofHours(1)));
        assertShowtimeStatus(event.id(), "ON_SALE");

        clock.set(start.plus(Duration.ofHours(2)));
        assertShowtimeStatus(event.id(), "SALES_CLOSED");

        clock.set(start.plus(Duration.ofHours(4)));
        assertShowtimeStatus(event.id(), "FINISHED");

        java.time.OffsetDateTime cancelledAt = jdbc.sql("""
                SELECT cancelled_at FROM showtime WHERE id = :id
                """)
                .param("id", showtime.id())
                .query(java.time.OffsetDateTime.class)
                .optional().orElse(null);

        assertThat(cancelledAt).isNull();
    }

    @Test
    void getAdminEvent_returns404_whenEventDoesNotExist() {
        client.get().uri(PATH, UUID.randomUUID())
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void getAdminEvent_returns401_whenTokenMissing() {
        client.get().uri(PATH, UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void getAdminEvent_returns403_whenUserIsBuyer() {
        client.get().uri(PATH, UUID.randomUUID())
                .headers(h -> h.setBearerAuth(tokenFor(Role.BUYER)))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    private String readDetails(UUID eventId) {
        return client.get().uri(PATH, eventId)
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private void assertShowtimeStatus(UUID eventId, String expected) {
        assertThat(JsonPath.<List<String>>read(
                readDetails(eventId), "$.showtimes[*].status"))
                .containsExactly(expected);
    }
}