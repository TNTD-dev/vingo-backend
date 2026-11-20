package vingo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class BuyerProfileFlowTest extends PaymentTestSupport {

    @Test
    void buyer_mustProvideInfoInPaymentRequest_andIsSavedOnOrder() {
        var buyer = catalog.buyer();
        var show = catalog.onSaleShowtime();
        var order = holdStandingOrder(buyer, show, 1);

        createPayment(buyerToken(buyer), order, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(422);

        var payment = createPayment(buyerToken(buyer), order, UUID.randomUUID().toString(),
                "{\"buyer\":{\"full_name\":\"Buyer Test\",\"phone\":\"+84901234567\",\"email\":\"buyer@test.vingo\"}}")
                .expectStatus().isEqualTo(201).expectBody(vingo.api.model.PaymentIntent.class).returnResult().getResponseBody();
        assertThat(payment).isNotNull();

        var saved = jdbc.sql("SELECT buyer_name, buyer_email, buyer_phone FROM ticket_order WHERE id = :id")
                .param("id", order).query().singleRow();
        assertThat(saved)
                .containsEntry("buyer_name", "Buyer Test")
                .containsEntry("buyer_email", "buyer@test.vingo")
                .containsEntry("buyer_phone", "+84901234567");

        // Lần gọi sau không cần truyền lại buyer (tái sử dụng payment OPEN và buyer đã lưu trên đơn)
        createPayment(buyerToken(buyer), order, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(201).expectBody().jsonPath("$.id").isEqualTo(payment.getId().toString());
        assertThat(jdbc.sql("SELECT count(*) FROM payment WHERE order_id = :id").param("id", order)
                .query(Integer.class).single()).isOne();
    }

    @Test
    void createPayment_rejectsAnonymousAndInvalidBuyerInput() {
        var buyer = catalog.buyer();
        var show = catalog.onSaleShowtime();
        var order = holdStandingOrder(buyer, show, 1);

        createPayment(null, order, UUID.randomUUID().toString(), "{}")
                .expectStatus().isUnauthorized();

        createPayment(buyerToken(buyer), order, UUID.randomUUID().toString(),
                "{\"buyer\":{\"full_name\":\" \",\"phone\":\"0901234567\",\"email\":\"invalid\"}}")
                .expectStatus().isBadRequest();

        var savedEmail = jdbc.sql("SELECT buyer_email FROM ticket_order WHERE id = :id")
                .param("id", order).query(String.class).optional().orElse(null);
        assertThat(savedEmail).isNull();
    }
}
