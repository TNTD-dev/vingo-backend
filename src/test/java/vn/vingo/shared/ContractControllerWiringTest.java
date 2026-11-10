package vn.vingo.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

import vn.vingo.api.SearchApi;
import vn.vingo.support.IntegrationTest;

/**
 * Controller implements interface sinh từ contract được gắn /api/v1 và
 * method chưa override trả 501 (không phải 404).
 */
@Import(ContractControllerWiringTest.StubSearchController.class)
class ContractControllerWiringTest extends IntegrationTest {

    @Test
    void contractController_isServedUnderApiV1() {
        client.get().uri("/api/v1/events").exchange().expectStatus().isEqualTo(501);
    }

    @Test
    void contractController_isNotServedWithoutPrefix() {
        client.get().uri("/events").exchange().expectStatus().is4xxClientError();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class StubSearchController implements SearchApi {
    }
}
