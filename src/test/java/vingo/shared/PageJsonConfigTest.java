package vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.ListMyOrders200Response;
import vingo.api.model.SearchEvents200Response;
import vingo.support.IntegrationTest;

class PageJsonConfigTest extends IntegrationTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void serialize_includesNullNextCursorForBookingAndSearchPages() {
        for (Object page : new Object[]{new ListMyOrders200Response(), new SearchEvents200Response()}) {
            String json = jsonMapper.writeValueAsString(page);
            assertThat(json).contains("\"next_cursor\":null");
            assertThat(jsonMapper.readTree(json).has("next_cursor")).isTrue();
            assertThat(jsonMapper.readTree(json).path("next_cursor").isNull()).isTrue();
        }
    }
}
