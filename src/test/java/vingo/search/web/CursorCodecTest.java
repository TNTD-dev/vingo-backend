package vingo.search.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vingo.search.InvalidSearchRequestException;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec();

    @Test
    void decode_returnsSamePayload_afterEncode() {
        UUID id = UUID.randomUUID();

        var payload = codec.decode(codec.encode("price", "500000", id, "sig"));

        assertThat(payload.s()).isEqualTo("price");
        assertThat(payload.k()).isEqualTo("500000");
        assertThat(payload.i()).isEqualTo(id);
        assertThat(codec.matches(payload, "price", "sig")).isTrue();
    }

    @Test
    void matches_returnsFalse_whenSortOrFilterDiffers() {
        var payload = codec.decode(codec.encode("price", "1", UUID.randomUUID(), "sig"));

        assertThat(codec.matches(payload, "starts_at", "sig")).isFalse();
        assertThat(codec.matches(payload, "price", "other")).isFalse();
    }

    @Test
    void decode_throwsValidationError_whenCursorIsCorrupt() {
        String notJson = Base64.getUrlEncoder().withoutPadding().encodeToString("khong phai json".getBytes());
        String missingFields = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"s\":\"price\"}".getBytes());

        for (String bad : new String[] {"???", "abc", notJson, missingFields}) {
            assertThatThrownBy(() -> codec.decode(bad)).isInstanceOf(InvalidSearchRequestException.class);
        }
    }
}
