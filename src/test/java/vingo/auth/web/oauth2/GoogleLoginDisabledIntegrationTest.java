package vingo.auth.web.oauth2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

import vingo.support.IntegrationTest;

/** Contract của các endpoint Google khi tính năng bị tắt. */
@TestPropertySource(properties = "vingo.google.enabled=false")
class GoogleLoginDisabledIntegrationTest extends IntegrationTest {

    /** Trang bắt đầu và callback cùng trả ProblemDetail 404 khi Google bị tắt. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/google", "/api/v1/auth/oauth2/callback/google"})
    void googleEndpoint_returns404_whenDisabled(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.detail").isEqualTo("Đăng nhập Google chưa được bật");
    }
}
