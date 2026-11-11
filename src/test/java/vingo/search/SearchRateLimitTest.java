package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.json.JsonMapper;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** Giới hạn request theo IP cho các endpoint search (đặt 3 request mỗi phút). */
@TestPropertySource(properties = "vingo.search.rate-limit.per-minute=3")
class SearchRateLimitTest extends IntegrationTest {

    // 10 giây sau đầu phút: cửa sổ cố định còn 50 giây.
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:10Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        new CatalogFixtures(jdbc, clock).reset();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void searchEvents_returns429_whenIpExceedsLimit() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/v1/events").exchange().expectStatus().isOk();
        }

        var result = client.get().uri("/api/v1/events").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "50")
                .expectBody(String.class).returnResult();

        assertThat(JSON.readTree(result.getResponseBody()).path("code").asString()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void searchEndpoints_shareOneCounterPerIp() {
        UUID eventId = new CatalogFixtures(jdbc, clock).event().create().id();
        client.get().uri("/api/v1/events").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/events/" + eventId).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/events/" + eventId + "/showtimes").exchange().expectStatus().isOk();

        client.get().uri("/api/v1/showtimes/" + UUID.randomUUID() + "/availability").exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void searchEvents_allowsRequestsAgain_inNextWindow() {
        for (int i = 0; i < 4; i++) {
            client.get().uri("/api/v1/events").exchange();
        }
        clock.set(NOW.plusSeconds(60));

        client.get().uri("/api/v1/events").exchange().expectStatus().isOk();
    }
}
