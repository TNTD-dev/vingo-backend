package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import java.time.OffsetDateTime;
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

import vingo.api.model.LoginRequest;
import vingo.api.model.RefreshResult;
import vingo.api.model.RefreshTokenRequest;
import vingo.api.model.TokenPair;
import vingo.api.model.UpdateMeRequest;
import vingo.api.model.User;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.CatalogFixture;

/** Dùng cấu hình chung trong application-test.yml: secure-cookies=false, khóa sau 3 lần sai trong 15 phút. */
class PasswordLoginIntegrationTest extends IntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String REFRESH_COOKIE = "vingo_refresh";
    private static final String PASSWORD = "StrongPassword#2026";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void activeBuyerAndAdmin_canLoginByEmailCaseInsensitive_withCorrectRoleAndRefreshLogout() {
        String buyerEmail = "Buyer." + UUID.randomUUID() + "@Vingo.Local";
        String buyerPhone = CatalogFixture.uniquePhone();
        UUID buyerId = insertUser(buyerEmail.toLowerCase(), buyerPhone, "Buyer User",
                passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);
        jdbc.sql("UPDATE user_account SET phone_verified_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.now(clock)).param("id", buyerId).update();

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
        assertThat(buyerTokens.getUser().getPhone()).isEqualTo(buyerPhone);
        assertThat(buyerTokens.getAccessToken()).isNotBlank();
        assertThat(buyerTokens.getRefreshToken()).isNotBlank();

        // Buyer cannot access admin endpoint
        client.get().uri("/api/v1/admin/venues")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerTokens.getAccessToken())
                .exchange()
                .expectStatus().isForbidden();

        // Cả số từng được xác minh trên hệ thống cũ cũng chỉ là thông tin liên hệ.
        client.post().uri(LOGIN).body(new LoginRequest(buyerPhone, PASSWORD))
                .exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");

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
        assertThat(rotated.getUser().getPhone()).isEqualTo(buyerPhone);

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
        UUID hardLocked = insertUser(adminLockedEmail, null, "Admin Locked", passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);
        jdbc.sql("UPDATE user_account SET admin_locked = true WHERE id = :id").param("id", hardLocked).update();
        clock.advance(Duration.ofMinutes(16));
        client.post().uri(LOGIN)
                .body(new LoginRequest(adminLockedEmail, PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.LOCKED)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCOUNT_LOCKED");

        // Simulate expired temporary lock -> correct password unlocks account and issues tokens
        jdbc.sql("UPDATE user_account SET locked_until = :past WHERE id = :id")
                .param("past", OffsetDateTime.now(clock).minusMinutes(1))
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
        assertThat(unlockedTokens.getUser().getEmailVerified()).isTrue();

        OffsetDateTime lockedUntilAfter = jdbc.sql("SELECT locked_until FROM user_account WHERE id = :id")
                .param("id", userId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        assertThat(lockedUntilAfter).isNull();
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

        OffsetDateTime lockedUntil = jdbc.sql("SELECT locked_until FROM user_account WHERE id = :id")
                .param("id", userId)
                .query(OffsetDateTime.class)
                .single();
        assertThat(lockedUntil).isAfter(OffsetDateTime.now(clock));
    }

    @Test
    void unverifiedPhone_returns401ForPhone_butAllowsEmailLogin() {
        String email = UUID.randomUUID() + "@phone-login.test";
        String phone = CatalogFixture.uniquePhone();
        UUID user = insertUser(email, phone, "Unverified phone", passwordEncoder.encode(PASSWORD),
                Role.BUYER, "ACTIVE", null, null);
        client.post().uri(LOGIN).body(new LoginRequest(phone, PASSWORD))
                .exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        client.post().uri(LOGIN).body(new LoginRequest(email, PASSWORD))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.user.id").isEqualTo(user.toString())
                .jsonPath("$.user.phone").isEqualTo(phone);
    }

    @Test
    void duplicateUnverifiedPhones_allowEachEmailLogin_andReturn401InsteadOf500ForPhone() {
        String phone = CatalogFixture.uniquePhone();
        String firstEmail = UUID.randomUUID() + "@phone-login.test";
        String secondEmail = UUID.randomUUID() + "@phone-login.test";
        String hash = passwordEncoder.encode(PASSWORD);
        UUID first = insertUser(firstEmail, phone, "First", hash, Role.BUYER, "ACTIVE", null, null);
        UUID second = insertUser(secondEmail, phone, "Second", hash, Role.BUYER, "ACTIVE", null, null);
        client.post().uri(LOGIN).body(new LoginRequest(firstEmail, PASSWORD))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user.id").isEqualTo(first.toString());
        client.post().uri(LOGIN).body(new LoginRequest(secondEmail, PASSWORD))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user.id").isEqualTo(second.toString());
        for (String identifier : List.of(phone, "0" + phone.substring(3))) {
            client.post().uri(LOGIN).body(new LoginRequest(identifier, PASSWORD))
                    .exchange().expectStatus().isUnauthorized().expectBody()
                    .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        }
    }

    @Test
    void invalidVietnamesePhone_returnsSame401CodeAsUnknownCredentials() {
        for (String identifier : List.of("+12025550123", "0912", "09abc45678")) {
            client.post().uri(LOGIN).body(new LoginRequest(identifier, PASSWORD))
                    .exchange().expectStatus().isUnauthorized().expectBody()
                    .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        }
    }

    @Test
    void updateMe_allowsSharingContactPhone_andClearsLegacyTimestamp() {
        UUID buyer = catalog.buyer();
        UUID other = catalog.buyer();
        jdbc.sql("UPDATE user_account SET phone_verified_at = :now WHERE id IN (:buyer, :other)")
                .param("now", OffsetDateTime.now(clock)).param("buyer", buyer).param("other", other).update();
        String contact = jdbc.sql("SELECT phone FROM user_account WHERE id = :id")
                .param("id", other).query(String.class).single();

        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(new UpdateMeRequest().phone(contact))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.phone").isEqualTo(contact);

        assertThat(jdbc.sql("SELECT phone_verified_at IS NULL FROM user_account WHERE id = :id")
                .param("id", buyer).query(Boolean.class).single()).isTrue();
    }

    private UUID insertUser(String email, String phone, String fullName, String passwordHash,
                            Role role, String status, String googleSub, OffsetDateTime lockedUntil) {
        OffsetDateTime emailVerifiedAt = "UNVERIFIED".equals(status) ? null : OffsetDateTime.now(clock);
        OffsetDateTime deletedAt = "DELETED".equals(status) ? OffsetDateTime.now(clock) : null;
        OffsetDateTime effectiveLockedUntil = lockedUntil != null ? lockedUntil
                : ("LOCKED".equals(status) ? OffsetDateTime.now(clock).plusMinutes(15) : null);
        return jdbc.sql("""
                        INSERT INTO user_account (email, phone, full_name, password_hash, google_sub, role,
                                                  email_verified_at, deleted_at, locked_until)
                        VALUES (:email, :phone, :fullName, :passwordHash, :googleSub, :role::user_role,
                                :emailVerifiedAt, :deletedAt, :lockedUntil)
                        RETURNING id
                        """)
                .param("email", email)
                .param("phone", phone)
                .param("fullName", fullName)
                .param("passwordHash", passwordHash)
                .param("googleSub", googleSub)
                .param("role", role.name())
                .param("emailVerifiedAt", emailVerifiedAt)
                .param("deletedAt", deletedAt)
                .param("lockedUntil", effectiveLockedUntil)
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
