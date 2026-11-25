package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vingo.api.model.AdminAccount;
import vingo.api.model.AdminAccountPage;
import vingo.api.model.LoginRequest;
import vingo.api.model.TokenPair;
import vingo.auth.internal.AdminAccountRepository;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

@TestPropertySource(properties = "vingo.auth.secure-cookies=false")
class AdminAccountIntegrationTest extends IntegrationTest {
    private static final String ROOT = "/api/v1/admin/users";
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean AdminAccountRepository accounts;

    @Test
    void accountEndpoints_requireAdmin() {
        var id = UUID.randomUUID();
        client.get().uri(ROOT).exchange().expectStatus().isUnauthorized();
        client.get().uri(ROOT + "/" + id).exchange().expectStatus().isUnauthorized();
        client.patch().uri(ROOT + "/" + id).body(Map.of("locked", true, "reason", "Test"))
                .exchange().expectStatus().isUnauthorized();
        String buyer = tokenFor(Role.BUYER);
        client.get().uri(ROOT).headers(h -> h.setBearerAuth(buyer)).exchange().expectStatus().isForbidden();
        client.get().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(buyer)).exchange().expectStatus().isForbidden();
        client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(buyer))
                .body(Map.of("role", "ADMIN", "reason", "Test"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void deleteMe_rejectsLastActiveAdmin() {
        String admin = tokenFor(Role.ADMIN);
        // The shared test database also contains admins created by other tests.
        doReturn(1L).when(accounts).activeAdmins(org.mockito.ArgumentMatchers.any());
        client.delete().uri("/api/v1/me").headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ACTION_STATE_INVALID");
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isOk();
    }

    @Test
    void list_filtersAndPaginates_withoutSecrets() {
        String admin = tokenFor(Role.ADMIN);
        String marker = UUID.randomUUID().toString();
        UUID first = user(marker + ".one");
        UUID second = user(marker + ".two");
        var page = client.get().uri(ROOT + "?q=" + marker + "&role=BUYER&status=ACTIVE&limit=1")
                .headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
                .expectBody(AdminAccountPage.class).returnResult().getResponseBody();
        assertThat(page.getData()).hasSize(1);
        assertThat(page.getNextCursor()).isNotBlank();
        var next = client.get().uri(ROOT + "?q=" + marker + "&limit=1&cursor=" + page.getNextCursor())
                .headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
                .expectBody(AdminAccountPage.class).returnResult().getResponseBody();
        assertThat(next.getData()).hasSize(1);
        assertThat(next.getData().getFirst().getId()).isNotEqualTo(page.getData().getFirst().getId());
        client.get().uri(ROOT + "/" + first).headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.password_hash").doesNotExist()
                .jsonPath("$.google_sub").doesNotExist().jsonPath("$.refresh_token").doesNotExist();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void update_promotesAndDemotes_revokesOldTokensAndAudits() {
        String admin = tokenFor(Role.ADMIN);
        UUID id = user("promotion");
        var old = login(id);
        var promoted = update(admin, id, Map.of("role", "ADMIN", "reason", "Phụ trách vận hành"));
        assertThat(promoted.getRole()).isEqualTo(AdminAccount.RoleEnum.ADMIN);
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(old.getAccessToken()))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/auth/refresh").body(Map.of("refresh_token", old.getRefreshToken()))
                .exchange().expectStatus().isUnauthorized();
        var elevated = login(id);
        client.get().uri(ROOT).headers(h -> h.setBearerAuth(elevated.getAccessToken()))
                .exchange().expectStatus().isOk();
        update(admin, id, Map.of("role", "BUYER", "reason", "Kết thúc phân công"));
        client.get().uri(ROOT).headers(h -> h.setBearerAuth(elevated.getAccessToken()))
                .exchange().expectStatus().isUnauthorized();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE entity_id = :id AND action = 'UPDATE_ACCOUNT'")
                .param("id", id).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void lock_blocksLoginAndRefresh_unlockDoesNotRestoreOldJwt() {
        String admin = tokenFor(Role.ADMIN);
        UUID id = user("lock");
        var old = login(id);
        assertThat(update(admin, id, Map.of("locked", true, "reason", "Tạm ngừng truy cập")).getAdminLocked()).isTrue();
        client.post().uri("/api/v1/auth/login").body(new LoginRequest(email(id), "Password#2026"))
                .exchange().expectStatus().isEqualTo(423);
        client.post().uri("/api/v1/auth/refresh").body(Map.of("refresh_token", old.getRefreshToken()))
                .exchange().expectStatus().isUnauthorized();
        update(admin, id, Map.of("locked", false, "reason", "Cho phép truy cập lại"));
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(old.getAccessToken()))
                .exchange().expectStatus().isUnauthorized();
        assertThat(login(id).getAccessToken()).isNotBlank();
    }

    @Test
    void update_rejectsSelfChangesInvalidBodiesAndMissingUsers() {
        UUID id = user("self");
        jdbc.sql("UPDATE user_account SET role = 'ADMIN' WHERE id = :id").param("id", id).update();
        String admin = tokenFor(id, Role.ADMIN);
        for (var body : java.util.List.of(Map.of("role", "BUYER", "reason", "Test"), Map.of("locked", true, "reason", "Test"))) {
            client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(admin)).body(body)
                    .exchange().expectStatus().isForbidden();
        }
        for (var body : java.util.List.of(Map.of("reason", "Test"), Map.of("role", "ROOT", "reason", "Test"),
                Map.of("locked", true, "reason", " "), Map.of("locked", true))) {
            client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(admin)).body(body)
                    .exchange().expectStatus().isBadRequest();
        }
        client.get().uri(ROOT + "/" + UUID.randomUUID()).headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isNotFound();
        client.patch().uri(ROOT + "/" + UUID.randomUUID()).headers(h -> h.setBearerAuth(admin))
                .body(Map.of("locked", true, "reason", "Test")).exchange().expectStatus().isNotFound();
        for (String filter : java.util.List.of("cursor=bad", "role=ROOT", "status=UNKNOWN", "limit=0")) {
            client.get().uri(ROOT + "?" + filter).headers(h -> h.setBearerAuth(admin))
                    .exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void update_rejectsUnverifiedDeletedAndGoogleOnlyPromotion_andReplayDoesNotAudit() {
        String admin = tokenFor(Role.ADMIN);
        UUID id = user("state");
        jdbc.sql("UPDATE user_account SET email_verified_at = NULL WHERE id = :id").param("id", id).update();
        client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(admin))
                .body(Map.of("locked", false, "reason", "Test")).exchange().expectStatus().isBadRequest();
        jdbc.sql("UPDATE user_account SET email_verified_at = :now, deleted_at = :now WHERE id = :id")
                .param("now", java.time.OffsetDateTime.now(clock)).param("id", id).update();
        client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(admin))
                .body(Map.of("locked", false, "reason", "Test")).exchange().expectStatus().isBadRequest();
        jdbc.sql("UPDATE user_account SET email_verified_at = :now, deleted_at = NULL, google_sub = :sub, password_hash = NULL WHERE id = :id")
                .param("now", java.time.OffsetDateTime.now(clock))
                .param("sub", UUID.randomUUID().toString()).param("id", id).update();
        client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(admin))
                .body(Map.of("role", "ADMIN", "reason", "Test")).exchange().expectStatus().isBadRequest();
        update(admin, id, Map.of("locked", true, "reason", "Test"));
        update(admin, id, Map.of("locked", true, "reason", "Test"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE entity_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    private UUID user(String name) {
        return jdbc.sql("""
                INSERT INTO user_account (email, full_name, password_hash, role, email_verified_at, phone)
                VALUES (:email, :name, :hash, 'BUYER', :now, :phone) RETURNING id
                """).param("email", name + UUID.randomUUID() + "@accounts.test").param("name", name)
                .param("phone", vingo.support.CatalogFixture.uniquePhone())
                .param("now", java.time.OffsetDateTime.now(clock))
                .param("hash", passwords.encode("Password#2026")).query(UUID.class).single();
    }

    private String email(UUID id) { return jdbc.sql("SELECT email FROM user_account WHERE id = :id").param("id", id).query(String.class).single(); }

    private TokenPair login(UUID id) {
        return client.post().uri("/api/v1/auth/login").body(new LoginRequest(email(id), "Password#2026"))
                .exchange().expectStatus().isOk().expectBody(TokenPair.class).returnResult().getResponseBody();
    }

    private AdminAccount update(String token, UUID id, Map<String, ?> body) {
        return client.patch().uri(ROOT + "/" + id).headers(h -> h.setBearerAuth(token)).body(body)
                .exchange().expectStatus().isOk().expectBody(AdminAccount.class).returnResult().getResponseBody();
    }
}
