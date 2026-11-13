package vingo.payment;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;

class BuyerProfileFlowTest extends PaymentTestSupport {
    @Test
    void buyer_canSaveProfileThenCreatePayment_withoutAffectingAnotherBuyer() {
        var buyer = catalog.buyer();
        var other = catalog.buyer();
        var show = catalog.onSaleShowtime();
        var order = holdStandingOrder(buyer, show, 1);
        client.get().uri("/api/v1/me/profile").headers(h -> h.setBearerAuth(buyerToken(buyer)))
                .exchange().expectStatus().isNotFound();
        createPayment(buyerToken(buyer), order, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(422);
        client.put().uri("/api/v1/me/profile").headers(h -> h.setBearerAuth(buyerToken(buyer)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"full_name\":\"Buyer Test\",\"phone\":\"0901234567\",\"email\":\"buyer@test.vingo\"}")
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/me/profile").headers(h -> h.setBearerAuth(buyerToken(buyer)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.email").isEqualTo("buyer@test.vingo");
        client.get().uri("/api/v1/me/profile").headers(h -> h.setBearerAuth(buyerToken(other)))
                .exchange().expectStatus().isNotFound();
        createPayment(buyerToken(buyer), order, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(201);
    }
    @Test
    void profile_rejectsAnonymousAndInvalidInput() {
        client.get().uri("/api/v1/me/profile").exchange().expectStatus().isUnauthorized();
        var buyer = catalog.buyer();
        client.put().uri("/api/v1/me/profile").headers(h -> h.setBearerAuth(buyerToken(buyer)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"full_name\":\" \",\"phone\":\"0901234567\",\"email\":\"invalid\"}")
                .exchange().expectStatus().isBadRequest();
        assertThat(jdbc.sql("SELECT count(*) FROM buyer_profile WHERE user_id = :id").param("id", buyer).query(Integer.class).single()).isZero();
    }
}
