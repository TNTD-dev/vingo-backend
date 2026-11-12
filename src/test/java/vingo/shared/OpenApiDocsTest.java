package vingo.shared;

import org.junit.jupiter.api.Test;

import vingo.support.IntegrationTest;

/**
 * Tài liệu OpenAPI do springdoc sinh phải khai báo bearerAuth giống contract,
 * để Swagger UI có nút Authorize và gắn được access token khi gọi thử.
 */
class OpenApiDocsTest extends IntegrationTest {

    @Test
    void apiDocs_declaresBearerAuthScheme() {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.securitySchemes.bearerAuth.type").isEqualTo("http")
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer")
                .jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").isEqualTo("JWT");
    }

    @Test
    void apiDocs_appliesBearerAuthToOperations() {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.security[0].bearerAuth").isArray();
    }
}
