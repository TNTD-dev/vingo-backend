package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class AdminUserReadIntegrationTest extends IntegrationTest {
    @Test
    void userReadApis_returnAvatarAndSafeCsv() {
        UUID user=catalog.buyer();
        String marker=UUID.randomUUID().toString();
        jdbc.sql("UPDATE user_account SET full_name='=SUM(1)',email=:email,avatar_url='https://example.com/avatar.png' WHERE id=:id")
                .param("id",user).param("email",marker+"@test.vingo").update();
        String admin=tokenFor(Role.ADMIN);
        client.get().uri("/api/v1/admin/users?q={q}&sort=name",marker).headers(h->h.setBearerAuth(admin))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data[0].avatar_url")
                .isEqualTo("https://example.com/avatar.png");
        String csv=client.get().uri("/api/v1/admin/users/export?q={q}",marker).headers(h->h.setBearerAuth(admin))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(csv).contains("\"'=SUM(1)\"").doesNotContain("password_hash","refresh_token");
    }

    @Test
    void userStatisticsAndExport_rejectBuyer() {
        String buyer=tokenFor(Role.BUYER);
        for(String path:new String[]{"/api/v1/admin/users/statistics","/api/v1/admin/users/export"})
            client.get().uri(path).headers(h->h.setBearerAuth(buyer)).exchange().expectStatus().isForbidden();
    }
}
