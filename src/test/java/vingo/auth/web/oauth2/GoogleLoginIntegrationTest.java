package vingo.auth.web.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import vingo.auth.internal.TokenSecrets;
import vingo.support.IntegrationTest;

@TestPropertySource(properties = {
        "vingo.google.enabled=true",
        "vingo.google.client-id=" + GoogleTestProvider.CLIENT_ID,
        "vingo.google.client-secret=test-google-secret",
        "vingo.google.rate-limit-per-minute=3",
        "vingo.auth.secure-cookies=true",
        "server.forward-headers-strategy=none"
})
@Import(GoogleLoginIntegrationTest.TestOAuthConfig.class)
class GoogleLoginIntegrationTest extends IntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String RETURN = ORIGIN + "/auth/google?google_login=";
    private static final String AVATAR = "https://lh3.googleusercontent.com/test-avatar";
    private static final String START = "/api/v1/auth/google";
    private static final String CALLBACK = "/api/v1/auth/oauth2/callback/google";
    private static final String BROWSER_COOKIE = "__Host-vingo_oauth_browser";
    private static final String SESSION_COOKIE = "__Host-vingo_session";
    private static final String DEVICE_COOKIE = "__Host-vingo_device";

    @Autowired
    private GoogleTestProvider provider;

    @Autowired
    private StringRedisTemplate redis;

    /** Mỗi test có cửa sổ rate limit riêng trên Redis của test context. */
    @BeforeEach
    void resetRateLimit() {
        redis.delete(redis.keys("vingo:auth:google:start:*"));
    }

    /** State chỉ được tiêu thụ khi callback gửi đúng cookie của trình duyệt. */
    @Test
    void start_storesPkceAndNonce_andCallbackConsumesStateOnlyForTheOriginalBrowser() {
        var attempt = startLogin();
        callbackRequest(attempt, "error", "access_denied")
                .exchange().expectStatus().isFound()
                .expectHeader().location(RETURN + "failed");
        assertThat(pending(attempt.state())).isEqualTo(1);

        callbackRequest(attempt, "error", "access_denied").cookie(BROWSER_COOKIE, attempt.browserSecret())
                .exchange().expectStatus().isFound()
                .expectHeader().location(RETURN + "failed");
        assertThat(pending(attempt.state())).isZero();
    }

    /** Request vượt giới hạn bị chặn trước cả thao tác xoá trạng thái hết hạn. */
    @Test
    void start_returns429_beforeWritingToPostgres_whenIpExceedsLimit() {
        for (int attempt = 0; attempt < 3; attempt++) {
            client.get().uri(START).exchange().expectStatus().isFound();
        }
        String sentinel = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO oauth_state (state_hash, browser_hash, request, expires_at)
                        VALUES (:hash, 'expired', '{}', now() - interval '1 minute')
                        """).param("hash", TokenSecrets.hash(sentinel)).update();
        int before = jdbc.sql("SELECT count(*) FROM oauth_state").query(Integer.class).single();

        client.get().uri(START).header("X-Forwarded-For", "192.0.2.42")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueMatches(HttpHeaders.RETRY_AFTER, "[1-9][0-9]*")
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");

        assertThat(pending(sentinel)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM oauth_state").query(Integer.class).single()).isEqualTo(before);
    }

    /** Hai callback HTTP đi qua filter chain, đổi code và tạo hai phiên cho một user. */
    @Test
    void firstAndReturningLogin_createTwoSessionsForOneAccount_andExposeAvatar() throws Exception {
        String email = UUID.randomUUID() + "@gmail.com";
        String subject = UUID.randomUUID().toString();
        var created = callback(subject, email, true);
        var returning = callback(subject, email, true);
        assertThat(created.getLocation()).hasToString(RETURN + "created");
        assertThat(returning.getLocation()).hasToString(RETURN + "success");
        String cookie = sessionCookie(created);
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax");
        assertThat(created.getOrEmpty(HttpHeaders.SET_COOKIE))
                .noneMatch(value -> value.contains("JSESSIONID") || value.contains("google-test-access"));
        assertThat(created.getOrEmpty(HttpHeaders.SET_COOKIE))
                .anyMatch(value -> value.startsWith("__Host-vingo_device=") && value.contains("Path=/;")
                        && value.contains("Secure") && value.contains("HttpOnly") && value.contains("SameSite=Lax"));
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE recipient=:email AND template='NEW_DEVICE'")
                .param("email", email).query(Integer.class).single()).isOne();
        String token = cookieValue(cookie);

        UUID userId = jdbc.sql("SELECT id FROM user_account WHERE google_sub = :sub AND password_hash IS NULL")
                .param("sub", subject).query(UUID.class).single();
        var stored = jdbc.sql("SELECT token_hash FROM login_session WHERE user_id = :id")
                .param("id", userId).query(String.class).list();
        assertThat(stored).hasSize(2).contains(TokenSecrets.hash(token)).doesNotContain(token);

        client.get().uri("/api/v1/me").cookie(SESSION_COOKIE, token).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(userId.toString()).jsonPath("$.avatar_url").isEqualTo(AVATAR);
    }

    /** Cookie máy quen được giữ qua callback; cùng IP không làm hai máy mới thành một. */
    @Test
    void familiarGoogleBrowserDoesNotQueueAnotherAlert() throws Exception {
        String email = UUID.randomUUID() + "@gmail.com";
        String subject = UUID.randomUUID().toString();
        var first = callback(subject, email, true);
        String deviceHeader = first.getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(DEVICE_COOKIE + "=")).findFirst().orElseThrow();
        assertThat(deviceHeader).contains("Max-Age=31536000;").doesNotContain("Domain=");
        String device = cookieValue(deviceHeader);
        assertThat(java.util.Base64.getUrlDecoder().decode(device)).hasSize(32);

        var familiar = callback(subject, email, true, device);
        assertThat(familiar.getLocation()).hasToString(RETURN + "success");
        assertThat(familiar.getOrEmpty(HttpHeaders.SET_COOKIE))
                .noneMatch(value -> value.startsWith(DEVICE_COOKIE + "="));
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE recipient=:email AND template='NEW_DEVICE'")
                .param("email", email).query(Integer.class).single()).isZero();

        callback(subject, email, true);
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE recipient=:email AND template='NEW_DEVICE'")
                .param("email", email).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM user_device d JOIN user_account u ON u.id=d.user_id WHERE u.google_sub=:subject")
                .param("subject", subject).query(Integer.class).single()).isEqualTo(2);
    }

    /** Email trùng tài khoản hiện có không cấp phiên hoặc tự liên kết. */
    @Test
    void matchingEmail_doesNotLinkOrCreateSession() throws Exception {
        String email = UUID.randomUUID() + "@gmail.com";
        UUID userId = jdbc.sql("""
                        INSERT INTO user_account (email, full_name, password_hash, role, email_verified_at)
                        VALUES (:email, 'Local', 'hash', 'ADMIN', now()) RETURNING id
                        """)
                .param("email", email).query(UUID.class).single();

        var response = callback(UUID.randomUUID().toString(), email.toUpperCase(Locale.ROOT), true);
        assertThat(response.getLocation()).hasToString(RETURN + "email_taken");
        assertThat(sessionCookie(response)).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM login_session WHERE user_id = :id")
                .param("id", userId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_account WHERE id = :id AND google_sub IS NULL")
                .param("id", userId).query(Integer.class).single()).isEqualTo(1);
    }

    /** Callback đồng thời dùng cùng Google subject chỉ tạo một bản ghi user. */
    @Test
    void simultaneousCallbacks_createOnlyOneAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@gmail.com";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> callback(subject, email, true).getLocation().toString());
            var second = executor.submit(() -> callback(subject, email, true).getLocation().toString());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(RETURN + "created", RETURN + "success");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM user_account WHERE google_sub = :subject")
                .param("subject", subject).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_device d JOIN user_account u ON u.id=d.user_id WHERE u.google_sub=:subject")
                .param("subject", subject).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE recipient=:email AND template='NEW_DEVICE'")
                .param("email", email).query(Integer.class).single()).isOne();
    }

    /** Claim email chưa xác minh không tạo tài khoản. */
    @Test
    void unverifiedEmail_doesNotCreateAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        var response = callback(subject, UUID.randomUUID() + "@gmail.com", false);
        assertThat(response.getLocation()).hasToString(RETURN + "failed");
        assertThat(jdbc.sql("SELECT count(*) FROM user_account WHERE google_sub = :subject")
                .param("subject", subject).query(Integer.class).single()).isZero();
    }

    @Test
    void googleSessionRequiresOriginForCookieLogoutAndIsImmediatelyRevoked() throws Exception {
        var response = callback(UUID.randomUUID().toString(), UUID.randomUUID() + "@gmail.com", true);
        String token = cookieValue(sessionCookie(response));
        client.post().uri("/api/v1/auth/logout").cookie(SESSION_COOKIE, token)
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.post().uri("/api/v1/auth/logout").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent().expectHeader().valueMatches(HttpHeaders.SET_COOKIE, ".*Max-Age=0.*");
        client.get().uri("/api/v1/me").cookie(SESSION_COOKIE, token)
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    /** Đếm yêu cầu OAuth2 còn trong PostgreSQL theo state ban đầu. */
    private int pending(String state) {
        return jdbc.sql("SELECT count(*) FROM oauth_state WHERE state_hash = :state")
                .param("state", TokenSecrets.hash(state)).query(Integer.class).single();
    }

    /** Bắt đầu luồng và lấy dữ liệu mà Google sẽ gửi lại khi xác thực. */
    private LoginAttempt startLogin() {
        var headers = client.get().uri(START).exchange().expectStatus().isFound()
                .returnResult(Void.class).getResponseHeaders();
        var location = headers.getLocation();
        assertThat(location).hasHost("accounts.google.com");
        var query = UriComponentsBuilder.fromUri(location).build(true).getQueryParams();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        return new LoginAttempt(UriUtils.decode(query.getFirst("state"), StandardCharsets.UTF_8),
                UriUtils.decode(query.getFirst("nonce"), StandardCharsets.UTF_8),
                cookieValue(headers.getFirst(HttpHeaders.SET_COOKIE)));
    }

    /** Gửi callback thật với code của Google mô phỏng và cookie của chính lượt đăng nhập. */
    private HttpHeaders callback(String subject, String email, boolean verified) throws Exception {
        return callback(subject, email, verified, null);
    }

    private HttpHeaders callback(String subject, String email, boolean verified, String device) throws Exception {
        var attempt = startLogin();
        String code = provider.issueCode(attempt.nonce(), subject, email, verified, AVATAR);
        var request = callbackRequest(attempt, "code", code).cookie(BROWSER_COOKIE, attempt.browserSecret());
        if (device != null) request.cookie(DEVICE_COOKIE, device);
        var response = request.exchange().expectStatus().isFound().returnResult(Void.class).getResponseHeaders();
        assertThat(pending(attempt.state())).isZero();
        return response;
    }

    /** Dựng callback cùng state cho cả nhánh code và lỗi từ Google. */
    private RestTestClient.RequestHeadersSpec<?> callbackRequest(LoginAttempt attempt, String parameter, String value) {
        return client.get().uri(builder -> builder.path(CALLBACK)
                .queryParam(parameter, value).queryParam("state", attempt.state()).build());
    }

    /** Lấy riêng cookie phiên vì callback còn trả lệnh xoá cookie OAuth2. */
    private static String sessionCookie(HttpHeaders headers) {
        return headers.getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "=")).findFirst().orElse(null);
    }

    /** Lấy giá trị cookie trước các thuộc tính Set-Cookie. */
    private static String cookieValue(String header) {
        assertThat(header).isNotNull();
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    private record LoginAttempt(String state, String nonce, String browserSecret) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOAuthConfig {

        /** Token endpoint giả và khóa công khai được dùng trong callback test. */
        @Bean
        GoogleTestProvider googleTestProvider() throws Exception {
            return new GoogleTestProvider();
        }

        /** Giữ registration thật và chuyển bước trao đổi code, xác minh chữ ký về loopback. */
        @Bean
        @Primary
        ClientRegistrationRepository testGoogleRegistration(
                @Qualifier("googleClientRegistration") ClientRegistrationRepository registration,
                GoogleTestProvider google) {
            var client = ClientRegistration.withClientRegistration(registration.findByRegistrationId("google"))
                    .tokenUri(google.endpoint("/token")).jwkSetUri(google.endpoint("/keys")).userInfoUri(null).build();
            return new InMemoryClientRegistrationRepository(client);
        }
    }
}
