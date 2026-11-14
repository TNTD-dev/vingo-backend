package vingo.catalog.web;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class AdminEventCreateIntegrationTest extends IntegrationTest {

    private static final String PATH = "/api/v1/admin/events";

    @Test
    void createEvent_returns201AndPersistsDraft() {
        String response = client.post().uri(PATH)
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "title": "  Trận đấu thử nghiệm  ",
                          "category": "FOOTBALL",
                          "description": "Giới thiệu trận đấu",
                          "attributes": {
                            "home_team": "Đội A",
                            "away_team": "Đội B"
                          },
                          "poster_mobile_url": "https://example.com/mobile.jpg",
                          "poster_desktop_url": "https://example.com/desktop.jpg",
                          "age_limit": 16,
                          "max_tickets_per_order": 4
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(JsonPath.<String>read(response, "$.status"))
                .isEqualTo("DRAFT");
        assertThat(JsonPath.<String>read(response, "$.title"))
                .isEqualTo("Trận đấu thử nghiệm");
        assertThat(JsonPath.<Integer>read(
                response, "$.max_tickets_per_order"))
                .isEqualTo(4);

        UUID id = UUID.fromString(JsonPath.read(response, "$.id"));

        var saved = jdbc.sql("""
                SELECT title, status, description, category,
                       poster_mobile_url, poster_desktop_url,
                       age_limit, max_tickets_per_order, published_at,
                       attributes ->> 'home_team' AS home_team
                FROM event
                WHERE id = :id
                """)
                .param("id", id)
                .query()
                .singleRow();

        assertThat(saved.get("title"))
                .isEqualTo("Trận đấu thử nghiệm");
        assertThat(saved.get("status")).isEqualTo("DRAFT");
        assertThat(saved.get("category")).isEqualTo("FOOTBALL");
        assertThat(saved.get("description"))
                .isEqualTo("Giới thiệu trận đấu");
        assertThat(saved.get("poster_mobile_url"))
                .isEqualTo("https://example.com/mobile.jpg");
        assertThat(saved.get("poster_desktop_url"))
                .isEqualTo("https://example.com/desktop.jpg");
        assertThat(saved.get("age_limit")).isEqualTo(16);
        assertThat(saved.get("max_tickets_per_order")).isEqualTo(4);
        assertThat(saved.get("home_team")).isEqualTo("Đội A");
        assertThat(saved.get("published_at")).isNull();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM showtime WHERE event_id = :id
                """)
                .param("id", id)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void createEvent_defaultsToTenTicketsAndEmptyAttributes() {
        String response = client.post().uri(PATH)
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "title": "Sự kiện nháp",
                          "category": "CONCERT"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        UUID id = UUID.fromString(JsonPath.read(response, "$.id"));

        assertThat(JsonPath.<Integer>read(
                response, "$.max_tickets_per_order"))
                .isEqualTo(10);

        boolean defaultsSaved = jdbc.sql("""
                SELECT max_tickets_per_order = 10
                   AND attributes = '{}'::jsonb
                   AND status = 'DRAFT'
                   AND published_at IS NULL
                FROM event WHERE id = :id
                """)
                .param("id", id)
                .query(Boolean.class)
                .single();

        assertThat(defaultsSaved).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"title\":\"A\"}",
            "{\"category\":\"FOOTBALL\"}",
            "{\"title\":\"   \",\"category\":\"FOOTBALL\"}",
            "{\"title\":\"A\",\"category\":\"INVALID\"}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"age_limit\":-1}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"max_tickets_per_order\":0}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"max_tickets_per_order\":21}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"attributes\":[]}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"poster_mobile_url\":\"/mobile.jpg\"}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"poster_desktop_url\":\"ftp://example.com/a.jpg\"}",
            "{\"title\":\"A\",\"category\":\"FOOTBALL\",\"poster_mobile_url\":\"https:/mobile.jpg\"}"
    })
    void createEvent_returns400AndDoesNotInsert_whenInputInvalid(
            String body
    ) {
        int before = eventCount();

        client.post().uri(PATH)
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        assertThat(eventCount()).isEqualTo(before);
    }

    @Test
    void createEvent_returns401_whenTokenMissing() {
        client.post().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title":"A","category":"FOOTBALL"}
                        """)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void createEvent_returns403_whenUserIsBuyer() {
        client.post().uri(PATH)
                .headers(h -> h.setBearerAuth(tokenFor(Role.BUYER)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title":"A","category":"FOOTBALL"}
                        """)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    private int eventCount() {
        return jdbc.sql("SELECT count(*) FROM event")
                .query(Integer.class)
                .single();
    }
}