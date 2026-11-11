package vingo.auth.web.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import vingo.api.model.RefreshResult;
import vingo.api.model.RefreshTokenRequest;
import vingo.auth.internal.TokenSecrets;
import vingo.support.IntegrationTest;

@TestPropertySource(properties = {
        "vingo.google.enabled=true",
        "vingo.google.client-id=test-google-client",
        "vingo.google.client-secret=test-google-secret"
})
class GoogleLoginIntegrationTest extends IntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String AVATAR = "https://lh3.googleusercontent.com/test-avatar";
    private static final String REFRESH = "/api/v1/auth/refresh";

    @Autowired
    private GoogleLoginHandler handler;

    @Test
    void start_storesPkceAndNonce_andCallbackConsumesStateOnlyForTheOriginalBrowser() {
        var response = client.get().uri("/api/v1/auth/google").exchange()
                .expectStatus().isFound().returnResult(Void.class);
        var location = response.getResponseHeaders().getLocation();
        assertThat(location).isNotNull();
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        var query = UriComponentsBuilder.fromUri(location).build(true).getQueryParams();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("nonce")).isNotBlank();
        String encodedState = query.getFirst("state");
        assertThat(encodedState).isNotBlank();
        String state = UriUtils.decode(encodedState, StandardCharsets.UTF_8);
        String browserCookie = response.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        URI callback = URI.create("/api/v1/auth/oauth2/callback/google?error=access_denied&state=" + encodedState);

        client.get().uri(builder -> builder.replacePath(callback.getPath()).replaceQuery(callback.getQuery()).build())
                .exchange().expectStatus().isFound()
                .expectHeader().location(ORIGIN + "/auth/google?google_login=failed");
        assertThat(pending(state)).isEqualTo(1);

        client.get().uri(builder -> builder.replacePath(callback.getPath()).replaceQuery(callback.getQuery()).build())
                .cookie(cookieName(browserCookie), cookieValue(browserCookie))
                .exchange().expectStatus().isFound()
                .expectHeader().location(ORIGIN + "/auth/google?google_login=failed");
        assertThat(pending(state)).isZero();
    }

    @Test
    void firstAndReturningLogin_createTwoSessionsForOneAccount_andExposeAvatar() throws Exception {
        String email = UUID.randomUUID() + "@gmail.com";
        String subject = UUID.randomUUID().toString();
        var created = callback(subject, email, true);
        var returning = callback(subject, email, true);
        assertThat(created.getRedirectedUrl()).isEqualTo(ORIGIN + "/auth/google?google_login=created");
        assertThat(returning.getRedirectedUrl()).isEqualTo(ORIGIN + "/auth/google?google_login=success");
        String cookie = created.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).startsWith("__Host-").contains("HttpOnly", "Secure", "SameSite=Lax");
        String token = cookieValue(cookie);

        UUID userId = jdbc.sql("SELECT id FROM users WHERE google_sub = :sub AND password_hash IS NULL")
                .param("sub", subject).query(UUID.class).single();
        var stored = jdbc.sql("SELECT refresh_token_hash FROM sessions WHERE user_id = :id")
                .param("id", userId).query(String.class).list();
        assertThat(stored).hasSize(2).contains(TokenSecrets.hash(token)).doesNotContain(token);

        var result = client.post().uri(REFRESH).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookieName(cookie), token).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.user.id").isEqualTo(userId.toString())
                .jsonPath("$.user.avatar_url").isEqualTo(AVATAR)
                .jsonPath("$.expires_in").isEqualTo(900)
                .jsonPath("$.refresh_token").doesNotExist()
                .returnResult();
        String rotated = cookieValue(result.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertThat(rotated).isNotEqualTo(token);
        client.post().uri(REFRESH).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookieName(cookie), token)
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void matchingEmail_doesNotLinkOrCreateSession() throws Exception {
        String email = UUID.randomUUID() + "@gmail.com";
        UUID userId = jdbc.sql("""
                        INSERT INTO users (email, full_name, password_hash, role, status)
                        VALUES (:email, 'Local', 'hash', 'ADMIN', 'ACTIVE') RETURNING id
                        """)
                .param("email", email).query(UUID.class).single();

        var response = callback(UUID.randomUUID().toString(), email.toUpperCase(Locale.ROOT), true);
        assertThat(response.getRedirectedUrl()).isEqualTo(ORIGIN + "/auth/google?google_login=email_taken");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM sessions WHERE user_id = :id")
                .param("id", userId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :id AND google_sub IS NULL")
                .param("id", userId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void simultaneousCallbacks_createOnlyOneAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@gmail.com";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> callback(subject, email, true).getRedirectedUrl());
            var second = executor.submit(() -> callback(subject, email, true).getRedirectedUrl());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
                    ORIGIN + "/auth/google?google_login=created", ORIGIN + "/auth/google?google_login=success");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE google_sub = :subject")
                .param("subject", subject).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void unverifiedEmail_doesNotCreateAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        var response = callback(subject, UUID.randomUUID() + "@gmail.com", false);
        assertThat(response.getRedirectedUrl()).isEqualTo(ORIGIN + "/auth/google?google_login=failed");
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE google_sub = :subject")
                .param("subject", subject).query(Integer.class).single()).isZero();
    }

    @Test
    void cookieRequiresTrustedOrigin_andLogoutRevokesTheRotatedSession() throws Exception {
        String cookie = callback(UUID.randomUUID().toString(), UUID.randomUUID() + "@gmail.com", true)
                .getHeader(HttpHeaders.SET_COOKIE);
        String token = cookieValue(cookie);
        client.post().uri(REFRESH).cookie(cookieName(cookie), token)
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        var pair = client.post().uri(REFRESH)
                .body(new RefreshTokenRequest(token))
                .exchange().expectStatus().isOk().expectBody(RefreshResult.class)
                .returnResult().getResponseBody();
        assertThat(pair).isNotNull();
        client.post().uri("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + pair.getAccessToken())
                .exchange().expectStatus().isNoContent().expectHeader().valueMatches(HttpHeaders.SET_COOKIE, ".*Max-Age=0.*");
        client.post().uri(REFRESH).body(new RefreshTokenRequest(pair.getRefreshToken()))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void refresh_rejectsMissingTokenAndMalformedBody() {
        client.post().uri(REFRESH).exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client.post().uri(REFRESH).body(Map.of()).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    private int pending(String state) {
        return jdbc.sql("SELECT count(*) FROM oauth_login_request WHERE state_hash = :state")
                .param("state", TokenSecrets.hash(state)).query(Integer.class).single();
    }

    private MockHttpServletResponse callback(String subject, String email, boolean verified) throws Exception {
        var idToken = OidcIdToken.withTokenValue("test-token").subject(subject)
                .claim("email", email).claim("email_verified", verified)
                .claim("name", "Google Test").claim("picture", AVATAR).build();
        var principal = new DefaultOidcUser(List.of(), idToken);
        var response = new MockHttpServletResponse();
        handler.success(new MockHttpServletRequest(), response, new TestingAuthenticationToken(principal, null));
        return response;
    }

    private static String cookieValue(String header) {
        assertThat(header).isNotNull();
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    private static String cookieName(String header) {
        assertThat(header).isNotNull();
        return header.substring(0, header.indexOf('='));
    }
}
