package vingo.auth.web;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriComponentsBuilder;

import vingo.auth.internal.AuthProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthWebConfig implements WebMvcConfigurer {

    private final String frontendOrigin;

    AuthWebConfig(AuthProperties properties) {
        URI frontend = properties.frontendReturnUrl();
        Assert.isTrue(frontend.getHost() != null && frontend.getUserInfo() == null
                        && ("https".equals(frontend.getScheme()) || "http".equals(frontend.getScheme())),
                "AUTH_FRONTEND_RETURN_URL must be an absolute HTTP(S) URL");
        int port = frontend.getPort();
        if (("https".equals(frontend.getScheme()) && port == 443) || ("http".equals(frontend.getScheme()) && port == 80)) {
            port = -1;
        }
        frontendOrigin = UriComponentsBuilder.newInstance().scheme(frontend.getScheme())
                .host(frontend.getHost()).port(port).build().toUriString();
    }

    String frontendOrigin() {
        return frontendOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/auth/refresh")
                .allowedOrigins(frontendOrigin).allowedMethods("POST").allowCredentials(true);
        registry.addMapping("/api/v1/auth/logout")
                .allowedOrigins(frontendOrigin).allowedMethods("POST").allowCredentials(true);
    }
}
