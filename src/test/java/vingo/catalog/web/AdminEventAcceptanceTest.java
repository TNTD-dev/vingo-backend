package vingo.catalog.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;

class AdminEventAcceptanceTest extends IntegrationTest {
    private String token;
    @BeforeEach void prepare() {clock.set(Instant.parse("2026-11-12T09:00:00Z"));token=tokenFor(Role.ADMIN);}
    @AfterEach void reset() {clock.reset();}
    private JsonNode json(String text) {return JsonMapper.builder().build().readTree(text);}
    private org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec publish(UUID id) {
        return client.post().uri("/api/v1/admin/events/{id}/publish",id).headers(h->h.setBearerAuth(token)).exchange();
    }
    @Test void draftToPublished_preservesBlockedSeatsAndOpensSalesByClock() {
        var layout=catalog.event(EventSpec.defaults().withCity("HN"));
        String created=client.post().uri("/api/v1/admin/events").headers(h->h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("title","EV7 acceptance","category","CONCERT"))
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        UUID event=UUID.fromString(json(created).path("id").asString());
        assertThat(json(created).path("status").asString()).isEqualTo("DRAFT");
        publish(event).expectStatus().isEqualTo(422);
        String session=client.post().uri("/api/v1/admin/events/{id}/showtimes",event).headers(h->h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of(
                        "hall_id",layout.hallId(),"seat_layout_id",layout.layoutId(),
                        "starts_at","2026-11-20T09:00:00Z","ends_at","2026-11-20T11:00:00Z",
                        "sale_opens_at","2026-11-13T09:00:00Z","sale_closes_at","2026-11-20T08:00:00Z"))
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        UUID showtime=UUID.fromString(json(session).path("id").asString());
        client.put().uri("/api/v1/admin/events/{id}/price-tiers",event).headers(h->h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(List.of(Map.of("name","Admission","price",100000)))
                .exchange().expectStatus().isOk();
        UUID tier=jdbc.sql("SELECT id FROM price_tier WHERE event_id=:id").param("id",event).query(UUID.class).single();
        client.put().uri("/api/v1/admin/events/{id}/zone-prices",event).headers(h->h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(List.of(
                        Map.of("zone_id",layout.seatedZoneId(),"price_tier_id",tier),
                        Map.of("zone_id",layout.standingZoneId(),"price_tier_id",tier)))
                .exchange().expectStatus().isOk();
        client.post().uri("/api/v1/admin/showtimes/{id}/seats/block",showtime).headers(h->h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("seat_ids",List.of(layout.seat(1)),"reason","Maintenance"))
                .exchange().expectStatus().isOk();
        // Rebuild a missing row while preserving existing BLOCKED inventory.
        jdbc.sql("DELETE FROM showtime_seat WHERE showtime_id=:id AND seat_id=:seat").param("id",showtime).param("seat",layout.seat(2)).update();
        publish(event).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("PUBLISHED");
        publish(event).expectStatus().isOk();
        assertThat(jdbc.sql("SELECT status FROM showtime WHERE id=:id").param("id",showtime).query(String.class).single()).isEqualTo("SCHEDULED");
        assertThat(jdbc.sql("SELECT status FROM showtime_seat WHERE showtime_id=:id AND seat_id=:seat").param("id",showtime).param("seat",layout.seat(1)).query(String.class).single()).isEqualTo("BLOCKED");
        assertThat(jdbc.sql("SELECT count(*) FROM showtime_seat WHERE showtime_id=:id").param("id",showtime).query(Long.class).single()).isEqualTo(10);
        client.get().uri("/api/v1/events/{id}",event).exchange().expectStatus().isOk();
        clock.set(Instant.parse("2026-11-13T09:00:00Z"));
        client.get().uri("/api/v1/showtimes/{id}/seatmap",showtime).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ON_SALE");
    }
    @Test void incompletePricing_rollsBackEventAndShowtimeStates() {
        var e=catalog.event(EventSpec.defaults().withCity("HN").withStatus("DRAFT"));
        var s=catalog.showtime(e,Window.draft());
        jdbc.sql("DELETE FROM zone_price WHERE event_id=:id").param("id",e.id()).update();
        publish(e.id()).expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("EVENT_INCOMPLETE");
        assertThat(jdbc.sql("SELECT status FROM event WHERE id=:id").param("id",e.id()).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT status FROM showtime WHERE id=:id").param("id",s.id()).query(String.class).single()).isEqualTo("DRAFT");
    }
    @Test void publish_checksAuthenticationMissingAndLockedEvents() {
        UUID id=UUID.randomUUID();
        client.post().uri("/api/v1/admin/events/{id}/publish",id).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/admin/events/{id}/publish",id).headers(h->h.setBearerAuth(tokenFor(Role.BUYER))).exchange().expectStatus().isForbidden();
        publish(id).expectStatus().isNotFound();
        for(String state:List.of("ENDED","CANCELLED")) {
            var e=catalog.event(EventSpec.defaults().withCity("HN").withStatus(state));
            publish(e.id()).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("EVENT_LOCKED");
        }
    }
}
