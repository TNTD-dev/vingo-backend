package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.api.model.LoginRequest;
import vingo.auth.AuthService;
import vingo.auth.internal.LoginClient;
import vingo.auth.internal.SessionCleanupJob;
import vingo.auth.internal.TokenSecrets;
import vingo.support.IntegrationTest;

class NewDeviceIntegrationTest extends IntegrationTest {
    private static final String DEVICE = "vingo_device";
    private static final String LOGIN = "/api/v1/auth/login";
    @Autowired PasswordEncoder passwords;
    @Autowired AuthService auth;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void logoutKeepsDeviceAndOnlyFirstLoginFromAnotherBrowserQueuesMail() {
        var buyer = account();
        var first = login(buyer, null);
        String deviceHeader = first.getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(DEVICE + "=")).findFirst().orElseThrow();
        assertThat(deviceHeader).contains("Path=/;", "Max-Age=31536000;", "HttpOnly", "SameSite=Lax");
        String device = HttpCookie.parse(deviceHeader).getFirst().getValue();
        assertThat(Base64.getUrlDecoder().decode(device)).hasSize(32);
        assertThat(deviceCount(buyer)).isOne();
        assertThat(alertCount(buyer)).isZero();
        client.get().uri("/api/v1/me").cookie(DEVICE, device).exchange().expectStatus().isUnauthorized();
        var logout = client.post().uri("/api/v1/auth/logout").headers(h -> h.setBearerAuth(sessionToken(first)))
                .exchange().expectStatus().isNoContent().returnResult(Void.class);
        assertThat(logout.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith(DEVICE + "="));
        clock.advance(Duration.ofHours(2));
        assertThat(login(buyer, device).getOrEmpty(HttpHeaders.SET_COOKIE))
                .noneMatch(header -> header.startsWith(DEVICE + "="));
        assertThat(jdbc.sql("SELECT last_seen_at > created_at FROM user_device WHERE device_hash=:hash")
                .param("hash", TokenSecrets.hash(device)).query(Boolean.class).single()).isTrue();
        assertThat(alertCount(buyer)).isZero();
        String next = TokenSecrets.generate();
        String session = sessionToken(login(buyer, next));
        login(buyer, next);
        assertThat(deviceCount(buyer)).isEqualTo(2);
        assertThat(alertCount(buyer)).isOne();
        assertThat(jdbc.sql("SELECT payload::text FROM notification_outbox WHERE recipient=:email AND template='NEW_DEVICE'")
                .param("email", buyer.getIdentifier()).query(String.class).single())
                .doesNotContain(next, session, TokenSecrets.hash(next), TokenSecrets.hash(session));

        var other = account();
        login(other, next);
        assertThat(alertCount(other)).isZero();
        client.delete().uri("/api/v1/me").headers(h -> h.setBearerAuth(session)).exchange().expectStatus().isNoContent();
        assertThat(jdbc.sql("SELECT count(*) FROM user_device WHERE device_hash=:hash")
                .param("hash", TokenSecrets.hash(next)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM user_device WHERE device_hash=:hash")
                .param("hash", TokenSecrets.hash(device)).query(Integer.class).single()).isZero();
        assertThat(deviceCount(other)).isOne();
    }

    @Test
    void deviceIdentityIgnoresIpAndUserAgentAndReplacesInvalidCookies() {
        var buyer = account();
        var first = auth.login(buyer.getIdentifier(), buyer.getPassword(), new LoginClient("Browser", "192.0.2.1", "invalid"));
        assertThat(Base64.getUrlDecoder().decode(first.newDeviceToken())).hasSize(32);
        assertThat(alertCount(buyer)).isZero();

        var familiar = auth.login(buyer.getIdentifier(), buyer.getPassword(),
                new LoginClient("Updated browser", "2001:db8::2", first.newDeviceToken()));
        assertThat(familiar.newDeviceToken()).isNull();
        assertThat(deviceCount(buyer)).isOne();
        assertThat(alertCount(buyer)).isZero();

        auth.login(buyer.getIdentifier(), buyer.getPassword(), new LoginClient("Updated browser", "2001:db8::2", null));
        assertThat(deviceCount(buyer)).isEqualTo(2);
        assertThat(alertCount(buyer)).isOne();
    }

    @Test
    void concurrentLoginsDistinguishTwoFirstDevicesAndDeduplicateAnotherNewDevice() throws Exception {
        var buyer = account();
        concurrentLogins(buyer, TokenSecrets.generate(), TokenSecrets.generate());
        assertThat(deviceCount(buyer)).isEqualTo(2);
        assertThat(alertCount(buyer)).isOne();
        String shared = TokenSecrets.generate();
        concurrentLogins(buyer, shared, shared);
        assertThat(deviceCount(buyer)).isEqualTo(3);
        assertThat(alertCount(buyer)).isEqualTo(2);
    }

    @Test
    void failedLoginAndRolledBackSessionLeaveNoDeviceOrAlert() {
        var buyer = account();
        client.post().uri(LOGIN).body(new LoginRequest(buyer.getIdentifier(), "wrong-password"))
                .exchange().expectStatus().isUnauthorized().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
        assertThat(deviceCount(buyer)).isZero();
        login(buyer, null);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            auth.login(buyer.getIdentifier(), buyer.getPassword(), new LoginClient("Browser", null, null));
            assertThat(alertCount(buyer)).isOne();
            status.setRollbackOnly();
        });
        assertThat(deviceCount(buyer)).isOne();
        assertThat(alertCount(buyer)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM login_session s JOIN user_account u ON u.id=s.user_id WHERE u.email=:email")
                .param("email", buyer.getIdentifier()).query(Integer.class).single()).isOne();
    }

    @Test
    void invalidAndScopedAddressesAreHandledOnBothLoginAndActivity() {
        var buyer = account();
        var login = auth.login(buyer.getIdentifier(), buyer.getPassword(), new LoginClient("Browser", "bad.invalid", null));
        var user = auth.authenticate(login.token(), "Browser", null, false).orElseThrow();
        assertThat(auth.sessions(user).getFirst().getLastIp()).isNull();
        clock.advance(Duration.ofMinutes(6));
        auth.authenticate(login.token(), "Browser", "fe80::1%eth0", true).orElseThrow();
        assertThat(auth.sessions(user).getFirst().getLastIp()).isEqualTo("fe80::1");
        clock.advance(Duration.ofMinutes(6));
        auth.authenticate(login.token(), "Browser", "no-such-host.invalid", true).orElseThrow();
        assertThat(auth.sessions(user).getFirst().getLastIp()).isNull();

        var scoped = auth.login(buyer.getIdentifier(), buyer.getPassword(),
                new LoginClient("Browser", "fe80::1%eth0", login.newDeviceToken()));
        var scopedUser = auth.authenticate(scoped.token(), "Browser", null, false).orElseThrow();
        assertThat(auth.sessions(scopedUser)).filteredOn(session -> Boolean.TRUE.equals(session.getCurrent()))
                .singleElement().satisfies(session -> assertThat(session.getLastIp()).isEqualTo("fe80::1"));
        assertThat(deviceCount(buyer)).isOne();
        assertThat(alertCount(buyer)).isZero();
    }

    @Test
    void cleanupDeletesOnlyDevicesOlderThanOneYear() {
        clock.set(java.time.Instant.parse("2026-11-25T12:00:00Z"));
        var buyer = account();
        String stale = TokenSecrets.generate();
        login(buyer, stale);
        login(buyer, null);
        var cutoff = OffsetDateTime.now(clock).minusYears(1);
        jdbc.sql("""
                UPDATE user_device SET created_at=:old, last_seen_at=CASE WHEN device_hash=:hash THEN :old ELSE :cutoff END
                WHERE user_id=(SELECT id FROM user_account WHERE email=:email)
                """).param("old", cutoff.minusDays(1)).param("cutoff", cutoff)
                .param("hash", TokenSecrets.hash(stale)).param("email", buyer.getIdentifier()).update();
        new SessionCleanupJob(jdbc, clock).cleanup();
        assertThat(deviceCount(buyer)).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM user_device WHERE device_hash=:hash")
                .param("hash", TokenSecrets.hash(stale)).query(Integer.class).single()).isZero();
    }

    private void concurrentLogins(LoginRequest buyer, String first, String second) throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.Stream.of(first, second).map(device -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return login(buyer, device);
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var result : futures) result.get(20, TimeUnit.SECONDS);
        }
    }

    private LoginRequest account() {
        String email = UUID.randomUUID() + "@device.test";
        String password = "Device-test-password";
        jdbc.sql("INSERT INTO user_account(email, full_name, password_hash, email_verified_at) VALUES (:email,'Buyer',:hash,now())")
                .param("email", email).param("hash", passwords.encode(password)).update();
        return new LoginRequest(email, password);
    }

    private HttpHeaders login(LoginRequest buyer, String device) {
        var request = client.post().uri(LOGIN);
        if (device != null) request.cookie(DEVICE, device);
        return request.body(buyer).exchange().expectStatus().isOk().returnResult(Void.class).getResponseHeaders();
    }

    private int deviceCount(LoginRequest buyer) {
        return jdbc.sql("SELECT count(*) FROM user_device d JOIN user_account u ON u.id=d.user_id WHERE u.email=:email")
                .param("email", buyer.getIdentifier()).query(Integer.class).single();
    }

    private int alertCount(LoginRequest buyer) {
        return jdbc.sql("SELECT count(*) FROM notification_outbox WHERE recipient=:email AND template='NEW_DEVICE'")
                .param("email", buyer.getIdentifier()).query(Integer.class).single();
    }
}
