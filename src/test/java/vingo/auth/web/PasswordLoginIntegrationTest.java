package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import vingo.api.model.LoginRequest;
import vingo.api.model.RefreshResult;
import vingo.api.model.RefreshTokenRequest;
import vingo.api.model.TokenPair;
import vingo.api.model.User;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

@TestPropertySource(properties = {
        "vingo.auth.secure-cookies=false",
        "vingo.auth.login.max-attempts=3",
        "vingo.auth.login.attempt-window=PT15M",
        "vingo.auth.login.lock-duration=PT15M"
})
class PasswordLoginIntegrationTest extends IntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String REFRESH_COOKIE = "vingo_refresh";
    private static final String PASSWORD = "StrongPassword#2026";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void activeBuyerAndAdmin_canLoginByEmailCaseInsensitive_andPhone_withCorrectRoleAndRefreshLogout() {
        String buyerEmail = "Buyer." + UUID.randomUUID() + "@Vingo.Local";
        String buyerPhone = "09" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000L);
        UUID buyerId = insertUser(buyerEmail.toLowerCase(), buyerPhone, "Buyer User",
                passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);

        var emailLogin = client.post().uri(LOGIN)
                .body(new LoginRequest("  " + buyerEmail.toUpperCase() + "  ", PASSWORD).device("Chrome"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.SET_COOKIE)
                .expectBody(TokenPair.class)
                .returnResult();

        TokenPair buyerTokens = emailLogin.getResponseBody();
        assertThat(buyerTokens).isNotNull();
        assertThat(buyerTokens.getUser().getId()).isEqualTo(buyerId);
        assertThat(buyerTokens.getUser().getRole()).isEqualTo(User.RoleEnum.BUYER);
        assertThat(buyerTokens.getAccessToken()).isNotBlank();
        assertThat(buyerTokens.getRefreshToken()).isNotBlank();

        // Buyer cannot access admin endpoint
        client.get().uri("/api/v1/admin/venues")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerTokens.getAccessToken())
                .exchange()
                .expectStatus().isForbidden();

        // Buyer can also login by phone
        TokenPair phoneTokens = client.post().uri(LOGIN)
                .body(new LoginRequest(buyerPhone, PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenPair.class)
                .returnResult()
                .getResponseBody();
        assertThat(phoneTokens).isNotNull();
        assertThat(phoneTokens.getUser().getId()).isEqualTo(buyerId);

        // Refresh rotation with cookie works
        String cookieHeader = emailLogin.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookieValue = extractCookieValue(cookieHeader, REFRESH_COOKIE);
        RefreshResult rotated = client.post().uri(REFRESH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(REFRESH_COOKIE, cookieValue)
                .exchange()
                .expectStatus().isOk()
                .expectBody(RefreshResult.class)
                .returnResult()
                .getResponseBody();
        assertThat(rotated).isNotNull();
        assertThat(rotated.getAccessToken()).isNotBlank();

        // Old refresh token is invalidated after rotation
        client.post().uri(REFRESH)
                .body(new RefreshTokenRequest().refreshToken(buyerTokens.getRefreshToken()))
                .exchange()
                .expectStatus().isUnauthorized();

        // Logout revokes current session
        client.post().uri(LOGOUT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotated.getAccessToken())
                .exchange()
                .expectStatus().isNoContent();

        String rotatedCookie = extractCookieValue(
                client.post().uri(LOGIN)
                        .body(new LoginRequest(buyerEmail, PASSWORD))
                        .exchange().expectStatus().isOk()
                        .returnResult(TokenPair.class).getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE),
                REFRESH_COOKIE);
        assertThat(rotatedCookie).isNotBlank();

        // Admin login returns ADMIN role and allows /api/v1/admin/**
        String adminEmail = "admin." + UUID.randomUUID() + "@vingo.local";
        insertUser(adminEmail, null, "Admin User", passwordEncoder.encode(PASSWORD), Role.ADMIN, "ACTIVE", null, null);

        TokenPair adminTokens = client.post().uri(LOGIN)
                .body(new LoginRequest(adminEmail, PASSWORD).device("AdminBrowser"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenPair.class)
                .returnResult()
                .getResponseBody();
        assertThat(adminTokens).isNotNull();
        assertThat(adminTokens.getUser().getRole()).isEqualTo(User.RoleEnum.ADMIN);

        int adminStatus = client.get().uri("/api/v1/admin/venues")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminTokens.getAccessToken())
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(adminStatus).isIn(200, 404);
    }

    @Test
    void invalidCredentials_googleOnly_unverified_andDeleted_returnSame401InvalidCredentials() {
        String activeEmail = "active." + UUID.randomUUID() + "@vingo.local";
        insertUser(activeEmail, null, "Active", passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);

        String googleEmail = "google." + UUID.randomUUID() + "@vingo.local";
        insertUser(googleEmail, null, "Google Only", null, Role.BUYER, "ACTIVE", "google-sub-" + UUID.randomUUID(), null);

        String unverifiedEmail = "unverified." + UUID.randomUUID() + "@vingo.local";
        insertUser(unverifiedEmail, null, "Unverified", passwordEncoder.encode(PASSWORD), Role.BUYER, "UNVERIFIED", null, null);

        String deletedEmail = "deleted." + UUID.randomUUID() + "@vingo.local";
        insertUser(deletedEmail, null, "Deleted", null, Role.BUYER, "DELETED", null, null);

        List<String> details = new ArrayList<>();
        for (var req : List.of(
                new LoginRequest("missing." + UUID.randomUUID() + "@vingo.local", PASSWORD),
                new LoginRequest(activeEmail, "WrongPassword#1"),
                new LoginRequest(googleEmail, PASSWORD),
                new LoginRequest(unverifiedEmail, PASSWORD),
                new LoginRequest(deletedEmail, PASSWORD))) {
            var res = client.post().uri(LOGIN)
                    .body(req)
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS")
                    .returnResult();
            details.add(new String(res.getResponseBodyContent()));
        }
        assertThat(details).hasSize(5);
    }

    @Test
    void failedAttempts_lockAccountTemporarily_andExpiredLockUnlocksOnSuccess() {
        String email = "locktest." + UUID.randomUUID() + "@vingo.local";
        UUID userId = insertUser(email, null, "Lock Test", passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);

        // 3 failed attempts -> 401 and account becomes LOCKED
        for (int i = 0; i < 3; i++) {
            client.post().uri(LOGIN)
                    .body(new LoginRequest(email, "WrongPassword#" + i))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        }

        // Next attempt (even with correct password) returns 423 ACCOUNT_LOCKED
        client.post().uri(LOGIN)
                .body(new LoginRequest(email, PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.LOCKED)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCOUNT_LOCKED");

        // Admin hard-locked account (locked_until = NULL) stays locked
        String adminLockedEmail = "adminlock." + UUID.randomUUID() + "@vingo.local";
        insertUser(adminLockedEmail, null, "Admin Locked", passwordEncoder.encode(PASSWORD), Role.BUYER, "LOCKED", null, null);
        client.post().uri(LOGIN)
                .body(new LoginRequest(adminLockedEmail, PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.LOCKED)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCOUNT_LOCKED");

        // Simulate expired temporary lock -> correct password unlocks account and issues tokens
        jdbc.sql("UPDATE users SET locked_until = :past WHERE id = :id")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .param("id", userId)
                .update();

        TokenPair unlockedTokens = client.post().uri(LOGIN)
                .body(new LoginRequest(email, PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenPair.class)
                .returnResult()
                .getResponseBody();
        assertThat(unlockedTokens).isNotNull();
        assertThat(unlockedTokens.getUser().getStatus()).isEqualTo(User.StatusEnum.ACTIVE);

        String statusAfter = jdbc.sql("SELECT status FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        assertThat(statusAfter).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentWrongAttempts_serializeSafelyAndLockAtThreshold() throws Exception {
        String email = "concurrent." + UUID.randomUUID() + "@vingo.local";
        UUID userId = insertUser(email, null, "Concurrent", passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);

        int threads = 6;
        var latch = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    latch.await();
                    return client.post().uri(LOGIN)
                            .body(new LoginRequest(email, "WrongConcurrent#1"))
                            .exchange()
                            .returnResult(String.class)
                            .getStatus()
                            .value();
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(pool.submit(task));
            }
            latch.countDown();
            for (var f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
        }

        long unauthorizedCount = statuses.stream().filter(s -> s == 401).count();
        long lockedCount = statuses.stream().filter(s -> s == 423).count();
        assertThat(unauthorizedCount).isEqualTo(3);
        assertThat(lockedCount).isEqualTo(3);

        int attemptsRecorded = jdbc.sql("SELECT count(*) FROM login_attempt WHERE user_id = :id AND success = false")
                .param("id", userId)
                .query(Integer.class)
                .single();
        assertThat(attemptsRecorded).isEqualTo(3);

        String status = jdbc.sql("SELECT status FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("LOCKED");
    }

    private UUID insertUser(String email, String phone, String fullName, String passwordHash,
                            Role role, String status, String googleSub, OffsetDateTime lockedUntil) {
        return jdbc.sql("""
                        INSERT INTO users (email, phone, full_name, password_hash, google_sub, role, status, locked_until)
                        VALUES (:email, :phone, :fullName, :passwordHash, :googleSub, :role, :status, :lockedUntil)
                        RETURNING id
                        """)
                .param("email", email)
                .param("phone", phone)
                .param("fullName", fullName)
                .param("passwordHash", passwordHash)
                .param("googleSub", googleSub)
                .param("role", role.name())
                .param("status", status)
                .param("lockedUntil", lockedUntil)
                .query(UUID.class)
                .single();
    }

    private static String extractCookieValue(String setCookieHeader, String name) {
        assertThat(setCookieHeader).isNotBlank();
        for (String part : setCookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        throw new AssertionError("Missing cookie " + name + " in " + setCookieHeader);
    }
}
