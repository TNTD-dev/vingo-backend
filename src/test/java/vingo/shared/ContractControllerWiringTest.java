package vingo.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.PaymentApi;
import vingo.support.IntegrationTest;

/**
 * Controller implements interface sinh từ contract được gắn /api/v1 và
 * method chưa override trả 501 (không phải 404).
 *
 * <p>Stub dùng một interface chưa module nào hiện thực (hiện là PaymentApi, endpoint GET public).
 * Module payment hiện thực {@code handleVnpayReturn} thì đổi sang interface khác còn trống.
 */
@Import(ContractControllerWiringTest.StubPaymentController.class)
class ContractControllerWiringTest extends IntegrationTest {

    @Test
    void contractController_isServedUnderApiV1() {
        client.get().uri("/api/v1/payments/vnpay/return").exchange().expectStatus().isEqualTo(501);
    }

    @Test
    void contractController_isNotServedWithoutPrefix() {
        client.get().uri("/payments/vnpay/return").exchange().expectStatus().is4xxClientError();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class StubPaymentController implements PaymentApi {
    }
}
