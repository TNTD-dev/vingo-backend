package vn.vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import vn.vingo.shared.logging.RequestIdFilter;
import vn.vingo.shared.security.Role;
import vn.vingo.support.IntegrationTest;

/** Kiểm tra hạ tầng chung: migration, health, lỗi ProblemDetail, phân quyền, request_id. */
class PlatformSmokeTest extends IntegrationTest {

    @Test
    void flyway_appliesAllMigrations() {
        Integer failed = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success")
                .query(Integer.class).single();
        Integer tables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN ('orders', 'showtime_seat', 'ticket', 'realtime_event')
                        """)
                .query(Integer.class).single();
        assertThat(failed).isZero();
        assertThat(tables).isEqualTo(4);
    }

    @Test
    void health_isPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void protectedEndpoint_returns401WithCode_whenNoToken() {
        client.get().uri("/api/v1/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void adminEndpoint_returns403WithCode_whenBuyer() {
        client.get().uri("/api/v1/admin/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Role.BUYER))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void unknownPath_returns404WithCode_whenAuthenticated() {
        client.get().uri("/api/v1/does-not-exist")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Role.BUYER))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void requestId_isEchoed_whenClientSendsOne() {
        client.get().uri("/actuator/health")
                .header(RequestIdFilter.HEADER, "agent-req-12345678")
                .exchange()
                .expectHeader().valueEquals(RequestIdFilter.HEADER, "agent-req-12345678");
    }
}
