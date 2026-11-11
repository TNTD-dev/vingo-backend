package vingo.auth.web.oauth2;

import java.net.URI;
import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;

import tools.jackson.databind.json.JsonMapper;
import vingo.auth.internal.GoogleAuthorizationRequestRepository;
import vingo.auth.internal.GoogleLoginRateLimiter;
import vingo.auth.web.AuthCookies;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "vingo.google", name = "enabled", havingValue = "true")
class GoogleOAuth2SecurityConfig {

    static final String START = "/api/v1/auth/google";
    static final String CALLBACK = "/api/v1/auth/oauth2/callback/google";
    static final RequestMatcher START_REQUEST = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, START);
    private static final RequestMatcher CALLBACK_REQUEST = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, CALLBACK);

    /** Đăng ký Google OIDC client với callback của Vingo và PKCE. */
    @Bean
    ClientRegistrationRepository googleClientRegistration(
            @Value("${vingo.google.client-id}") String clientId,
            @Value("${vingo.google.client-secret}") String clientSecret,
            @Value("${vingo.google.redirect-uri}") URI redirectUri) {
        Assert.hasText(clientId, "GOOGLE_CLIENT_ID is required");
        Assert.hasText(clientSecret, "GOOGLE_CLIENT_SECRET is required");
        Assert.isTrue(CALLBACK.equals(redirectUri.getPath()), "GOOGLE_REDIRECT_URI must end with " + CALLBACK);
        var google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId).clientSecret(clientSecret).redirectUri(redirectUri.toString())
                .scope("openid", "email", "profile")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    /** Áp dụng filter chain riêng cho trang bắt đầu và callback Google. */
    @Bean
    @Order(1)
    SecurityFilterChain googleOAuth2Security(HttpSecurity http, GoogleAuthorizationRequestRepository requests,
                                            JsonMapper json, AuthCookies cookies, Clock clock,
                                            GoogleLoginHandler handler, StringRedisTemplate redis,
                                            @Value("${vingo.google.rate-limit-per-minute}") int rateLimit) throws Exception {
        http.securityMatcher(new OrRequestMatcher(START_REQUEST, CALLBACK_REQUEST))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new GoogleLoginStartFilter(new GoogleLoginRateLimiter(redis, rateLimit), json),
                        OAuth2AuthorizationRequestRedirectFilter.class)
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth")
                                .authorizationRequestRepository(new GoogleAuthorizationRequests(requests, json, cookies, clock)))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK))
                        .authorizedClientRepository(new DiscardGoogleTokens())
                        .successHandler(handler::success)
                        .failureHandler(handler::failure));
        return http.build();
    }

    private static class DiscardGoogleTokens implements OAuth2AuthorizedClientRepository {

        /** Google credentials chỉ dùng trong callback, nên kho này luôn rỗng. */
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal,
                                                                        HttpServletRequest request) {
            return null;
        }

        /** Bỏ authorized client sau khi Spring dùng xong thông tin Google. */
        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                                         HttpServletRequest request, HttpServletResponse response) {
            // Google credentials are only needed during the callback.
        }

        /** Không có authorized client được lưu để xoá. */
        @Override
        public void removeAuthorizedClient(String id, Authentication principal, HttpServletRequest request,
                                           HttpServletResponse response) {
        }
    }
}
