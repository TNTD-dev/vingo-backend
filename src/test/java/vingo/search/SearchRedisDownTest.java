package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** Redis không kết nối được: cache và rate limit đều bỏ qua, các endpoint search vẫn trả đúng từ DB. */
@Import(SearchRedisDownTest.DeadRedis.class)
@TestPropertySource(properties = "vingo.search.rate-limit.per-minute=1")
class SearchRedisDownTest extends IntegrationTest {

    /** Trỏ Redis tới cổng không có dịch vụ nào lắng nghe. */
    @TestConfiguration(proxyBeanMethods = false)
    static class DeadRedis {

        @Bean
        @Primary
        DataRedisConnectionDetails deadRedis() {
            return new DataRedisConnectionDetails() {
                @Override
                public Standalone getStandalone() {
                    return Standalone.of("127.0.0.1", 1);
                }
            };
        }
    }

    private UUID eventId;
    private UUID showtimeId;

    @BeforeEach
    void setUp() {
        var fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
        var created = fixtures.event().showtime(s -> s.onSale().seated(2)).create();
        eventId = created.id();
        showtimeId = created.showtimeId();
    }

    @Test
    void searchEndpoints_stillWork_whenRedisDown() {
        // Hai vòng: vòng đầu miss + put lỗi, vòng sau get lỗi; đều phải trả 200 từ DB.
        for (int round = 0; round < 2; round++) {
            ok("/api/v1/events");
            ok("/api/v1/events/" + eventId);
            ok("/api/v1/events/" + eventId + "/showtimes");
            ok("/api/v1/showtimes/" + showtimeId + "/seatmap");
            String availability = ok("/api/v1/showtimes/" + showtimeId + "/availability");
            assertThat(availability).contains("\"seats_left\":2");
        }
    }

    @Test
    void searchEvents_notRateLimited_whenRedisDown() {
        // Giới hạn đặt là 1 request/phút; Redis sập thì không đếm được nên không chặn.
        for (int i = 0; i < 5; i++) {
            ok("/api/v1/events");
        }
    }

    private String ok(String path) {
        return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                client.get().uri(path).exchange().expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody());
    }
}
