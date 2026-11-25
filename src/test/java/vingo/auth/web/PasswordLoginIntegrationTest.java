package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import vingo.auth.internal.SessionCleanupJob;
import vingo.auth.internal.TokenSecrets;
import vingo.api.model.LoginRequest;
import vingo.api.model.LoginResult;
import vingo.api.model.UpdateMeRequest;
import vingo.api.model.User;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.CatalogFixture;

/** Dùng cấu hình chung trong application-test.yml: secure-cookies=false, khóa sau 3 lần sai trong 15 phút. */
class PasswordLoginIntegrationTest extends IntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String SESSION_COOKIE = "vingo_session";
    private static final String PASSWORD = "StrongPassword#2026";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void activeBuyerAndAdmin_canLoginByEmailCaseInsensitive_withCorrectRoleAndLogout() {
        String buyerEmail = "Buyer." + UUID.randomUUID() + "@Vingo.Local";
        String buyerPhone = CatalogFixture.uniquePhone();
        UUID buyerId = insertUser(buyerEmail.toLowerCase(), buyerPhone, "Buyer User",
                passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);
        jdbc.sql("UPDATE user_account SET phone_verified_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.now(clock)).param("id", buyerId).update();

        var emailLogin = client.post().uri(LOGIN)
                .body(new LoginRequest("  " + buyerEmail.toUpperCase() + "  ", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.SET_COOKIE)
                .expectBody(LoginResult.class)
                .returnResult();

        LoginResult buyerTokens = emailLogin.getResponseBody();
        assertThat(buyerTokens).isNotNull();
        assertThat(buyerTokens.getUser().getId()).isEqualTo(buyerId);
        assertThat(buyerTokens.getUser().getRole()).isEqualTo(User.RoleEnum.BUYER);
        assertThat(buyerTokens.getUser().getPhone()).isEqualTo(buyerPhone);
        String token = sessionToken(emailLogin.getResponseHeaders());

        // Buyer cannot access admin endpoint
        client.get().uri("/api/v1/admin/venues")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden();

        // Cả số từng được xác minh trên hệ thống cũ cũng chỉ là thông tin liên hệ.
        client.post().uri(LOGIN).body(new LoginRequest(buyerPhone, PASSWORD))
                .exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");

        client.get().uri("/api/v1/me").cookie(SESSION_COOKIE, token).exchange().expectStatus().isOk();
        client.post().uri(LOGOUT).header(HttpHeaders.ORIGIN, ORIGIN).cookie(SESSION_COOKIE, token)
                .exchange().expectStatus().isNoContent();
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();

        // Admin login returns ADMIN role and allows /api/v1/admin/**
        String adminEmail = "admin." + UUID.randomUUID() + "@vingo.local";
        insertUser(adminEmail, null, "Admin User", passwordEncoder.encode(PASSWORD), Role.ADMIN, "ACTIVE", null, null);

        var adminLogin = client.post().uri(LOGIN)
                .body(new LoginRequest(adminEmail, PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResult.class)
                .returnResult();
        var adminTokens = adminLogin.getResponseBody();
        assertThat(adminTokens).isNotNull();
        assertThat(adminTokens.getUser().getRole()).isEqualTo(User.RoleEnum.ADMIN);

        int adminStatus = client.get().uri("/api/v1/admin/venues")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken(adminLogin.getResponseHeaders()))
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

        LoginResult unlockedTokens = client.post().uri(LOGIN)
                .body(new LoginRequest(email, PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResult.class)
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

    @Test
    void sessionCookieIsTheOnlyCredentialAndInvalidCookieAllowsPublicRequests() {
        var identity = newLoginAccount();
        String invalid = TokenSecrets.generate();
        client.get().uri("/api/v1/events").cookie(SESSION_COOKIE, invalid).exchange().expectStatus().isOk();
        var login = client.post().uri(LOGIN).cookie(SESSION_COOKIE, invalid).body(identity)
                .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody().jsonPath("$.user.id").exists().jsonPath("$.length()").isEqualTo(1).returnResult();
        var headers = login.getResponseHeaders();
        assertThat(headers.getFirst(HttpHeaders.SET_COOKIE)).startsWith("vingo_session=")
                .contains("Path=/;", "HttpOnly", "SameSite=Lax", "Max-Age=").doesNotContain("Secure");
        String token = sessionToken(headers);
        String hash = jdbc.sql("""
                SELECT s.token_hash FROM login_session s JOIN user_account u ON u.id=s.user_id WHERE u.email=:email
                """).param("email", identity.getIdentifier()).query(String.class).single();
        assertThat(hash).isEqualTo(TokenSecrets.hash(token)).isNotEqualTo(token);
        client.get().uri("/api/v1/events").cookie(SESSION_COOKIE, token).header(HttpHeaders.AUTHORIZATION, "Basic bad")
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client.get().uri("/api/v1/me").cookie(SESSION_COOKIE, token).headers(h -> h.setBearerAuth(invalid))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void csrfChecksOriginForAnonymousCookieAndBearerWrites() {
        var identity = newLoginAccount();
        client.post().uri(LOGIN).header(HttpHeaders.ORIGIN, "https://other.invalid").body(identity)
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        String token = login(identity);
        client.post().uri(LOGOUT).cookie(SESSION_COOKIE, token).exchange().expectStatus().isForbidden();
        client.post().uri(LOGOUT).headers(h -> h.setBearerAuth(token)).header(HttpHeaders.ORIGIN, "https://other.invalid")
                .exchange().expectStatus().isForbidden();
        client.post().uri(LOGOUT).cookie(SESSION_COOKIE, token).header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange().expectStatus().isNoContent();
        String other = login(identity);
        client.post().uri(LOGOUT).headers(h -> h.setBearerAuth(other)).exchange().expectStatus().isNoContent();
    }

    @Test
    void sessionListIsOrderedAndPrivateAndDeleteChecksOwnership() {
        var identity = newLoginAccount();
        String first = login(identity);
        clock.advance(Duration.ofSeconds(1));
        String second = login(identity);
        var sessions = client.get().uri("/api/v1/me/sessions").headers(h -> h.setBearerAuth(first))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2).jsonPath("$[0].current").isEqualTo(false)
                .jsonPath("$[1].current").isEqualTo(true).jsonPath("$[0].user_agent").isEqualTo("SessionTest")
                .jsonPath("$[0].last_ip").exists().jsonPath("$[0].created_at").exists()
                .jsonPath("$[0].last_seen_at").exists().jsonPath("$[0].expires_at").exists()
                .jsonPath("$[0].token_hash").doesNotExist().jsonPath("$[0].token").doesNotExist().returnResult();
        UUID id = sessionId(second);
        assertThat(new String(sessions.getResponseBodyContent())).doesNotContain(second);
        String outsider = tokenFor(Role.BUYER);
        client.delete().uri("/api/v1/me/sessions/" + id).headers(h -> h.setBearerAuth(outsider))
                .exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/me/sessions/" + id).headers(h -> h.setBearerAuth(first))
                .exchange().expectStatus().isNoContent().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
        expectRevoked(second);
        client.delete().uri("/api/v1/me/sessions/" + id).headers(h -> h.setBearerAuth(first))
                .exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/me/sessions/" + sessionId(first)).headers(h -> h.setBearerAuth(first))
                .exchange().expectStatus().isNoContent().expectHeader().valueMatches(HttpHeaders.SET_COOKIE, ".*Max-Age=0.*");
        expectRevoked(first);
    }

    @Test
    void passwordChangeRotatesCurrentSessionAndLogoutAllRevokesBothDevices() {
        clock.set(java.time.Instant.parse("2026-11-25T00:00:00Z"));
        var identity = newLoginAccount();
        String first = login(identity);
        String second = login(identity);
        UUID id = sessionId(first);
        var originalExpiry = catalog.now().plusDays(7);
        clock.advance(Duration.ofDays(5));
        String nextPassword = PASSWORD + "new";
        var headers = client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(first))
                .body(new UpdateMeRequest().currentPassword(PASSWORD).newPassword(nextPassword))
                .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .returnResult(Void.class).getResponseHeaders();
        String replacement = sessionToken(headers);
        assertThat(sessionId(replacement)).isEqualTo(id);
        assertThat(headers.getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=172800;");
        assertThat(jdbc.sql("SELECT expires_at FROM login_session WHERE id=:id")
                .param("id", id).query(OffsetDateTime.class).single()).isEqualTo(originalExpiry);
        expectRevoked(first);
        expectRevoked(second);
        String third = login(new LoginRequest(identity.getIdentifier(), nextPassword));
        client.post().uri(LOGOUT).headers(h -> h.setBearerAuth(replacement)).body(java.util.Map.of("all_devices", true))
                .exchange().expectStatus().isNoContent();
        expectRevoked(replacement);
        expectRevoked(third);
    }

    @Test
    void activityIsSampledEveryFiveMinutesAndPasswordLockDoesNotRevokeSession() {
        clock.set(java.time.Instant.parse("2026-11-25T00:00:00Z"));
        var identity = newLoginAccount();
        String token = login(identity);
        UUID id = sessionId(token);
        jdbc.sql("UPDATE user_account SET locked_until=:until WHERE email=:email")
                .param("until", catalog.now().plusHours(1)).param("email", identity.getIdentifier()).update();
        var initial = catalog.now();
        clock.advance(Duration.ofMinutes(4));
        client.get().uri("/api/v1/me").header(HttpHeaders.USER_AGENT, "later").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        assertThat(lastSeen(id)).isEqualTo(initial);
        clock.advance(Duration.ofMinutes(1));
        client.get().uri("/api/v1/me/sessions").header(HttpHeaders.USER_AGENT, "x".repeat(520))
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].user_agent").isEqualTo("x".repeat(512));
        assertThat(lastSeen(id)).isEqualTo(catalog.now());
    }

    @Test
    void regularlyUsedSessionExpiresExactlySevenDaysAfterLogin() {
        clock.set(java.time.Instant.parse("2026-11-25T00:00:00Z"));
        var identity = newLoginAccount();
        var login = client.post().uri(LOGIN).body(identity).exchange().expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.SET_COOKIE, ".*Max-Age=604800;.*").returnResult(Void.class);
        String token = sessionToken(login.getResponseHeaders());
        for (int day = 1; day <= 6; day++) {
            clock.advance(Duration.ofDays(1));
            client.get().uri("/api/v1/me").cookie(SESSION_COOKIE, token).exchange().expectStatus().isOk();
        }
        clock.advance(Duration.ofDays(1).minusSeconds(1));
        client.get().uri("/api/v1/me").cookie(SESSION_COOKIE, token).exchange().expectStatus().isOk();
        clock.advance(Duration.ofSeconds(1));
        expectRevoked(token);
    }

    @Test
    void cleanupOnlyDeletesExpiredSessionsAndKeepsQuietDevicesListedAndUsable() {
        clock.set(java.time.Instant.parse("2026-11-25T00:00:00Z"));
        var identity = newLoginAccount();
        String expired = login(identity);
        UUID expiredId = sessionId(expired);
        UUID user = jdbc.sql("SELECT user_id FROM login_session WHERE id=:id")
                .param("id", expiredId).query(UUID.class).single();
        String quiet = tokenFor(user, Role.BUYER);
        UUID quietId = sessionId(quiet);
        clock.advance(Duration.ofDays(8));
        String fresh = login(identity);
        var job = new SessionCleanupJob(jdbc, clock);
        job.cleanup();
        job.cleanup();
        assertThat(jdbc.sql("SELECT count(*) FROM login_session WHERE id=:id")
                .param("id", expiredId).query(Integer.class).single()).isZero();
        client.get().uri("/api/v1/me/sessions").headers(h -> h.setBearerAuth(fresh)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2).jsonPath("$[1].id").isEqualTo(quietId.toString());
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(quiet)).exchange().expectStatus().isOk();
    }

    private LoginRequest newLoginAccount() {
        String email = UUID.randomUUID() + "@session.test";
        insertUser(email, null, "Session test", passwordEncoder.encode(PASSWORD), Role.BUYER, "ACTIVE", null, null);
        return new LoginRequest(email, PASSWORD);
    }

    private String login(LoginRequest identity) {
        return sessionToken(client.post().uri(LOGIN).header(HttpHeaders.USER_AGENT, "SessionTest").body(identity)
                .exchange().expectStatus().isOk().returnResult(Void.class).getResponseHeaders());
    }

    private UUID sessionId(String token) {
        return jdbc.sql("SELECT id FROM login_session WHERE token_hash=:hash")
                .param("hash", TokenSecrets.hash(token)).query(UUID.class).single();
    }

    private OffsetDateTime lastSeen(UUID id) {
        return jdbc.sql("SELECT last_seen_at FROM login_session WHERE id=:id")
                .param("id", id).query(OffsetDateTime.class).single();
    }

    private void expectRevoked(String token) {
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
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
}
