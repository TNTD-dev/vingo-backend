package vingo.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khai báo bearerAuth cho tài liệu springdoc, khớp {@code components.securitySchemes} và {@code security}
 * mặc định trong contracts/vingo-openapi.yaml, để Swagger UI có nút Authorize.
 *
 * <p>Code sinh từ contract không mang annotation OpenAPI ({@code annotationLibrary=none}) nên springdoc
 * không biết endpoint nào public: mọi endpoint đều hiện ổ khoá. Gửi kèm token tới endpoint public
 * vẫn chạy bình thường. Quyền thật vẫn do {@code SecurityConfig} quyết định.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI vingoOpenApi(@Value("${vingo.auth.secure-cookies}") boolean secure) {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("opaque"))
                        .addSecuritySchemes("sessionCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name(secure ? "__Host-vingo_session" : "vingo_session")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
                .addSecurityItem(new SecurityRequirement().addList("sessionCookie"));
    }
}
