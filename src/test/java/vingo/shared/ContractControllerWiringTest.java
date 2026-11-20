package vingo.shared;

import org.junit.jupiter.api.Test;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class ContractControllerWiringTest extends IntegrationTest {

    @Test
    void contractController_isServedUnderApiV1() {
        var venue = catalog.event().venueId();
        client.get().uri("/api/v1/admin/venues/{id}", venue)
                .headers(headers -> headers.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void contractController_isNotServedWithoutPrefix() {
        var venue = catalog.event().venueId();
        client.get().uri("/admin/venues/{id}", venue)
                .headers(headers -> headers.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange()
                .expectStatus().is4xxClientError();
    }
}
