package vingo.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.ChatApi;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

/**
 * Controller implements interface sinh từ contract được gắn /api/v1 và method
 * chưa override trả 501 (không phải 404).
 *
 * <p>
 * Stub dùng một interface chưa module nào hiện thực (hiện là ChatApi). Module
 * chat hiện thực {@code createConversation} thì đổi sang interface khác còn
 * trống.
 */
@Import(ContractControllerWiringTest.StubChatController.class)
class ContractControllerWiringTest extends IntegrationTest {

    @Test
    void contractController_isServedUnderApiV1() {
        String token = tokenFor(Role.BUYER);
        client.post().uri("/api/v1/chat/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isEqualTo(501);
    }

    @Test
    void contractController_isNotServedWithoutPrefix() {
        String token = tokenFor(Role.BUYER);
        client.post().uri("/chat/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class StubChatController implements ChatApi {
    }
}
