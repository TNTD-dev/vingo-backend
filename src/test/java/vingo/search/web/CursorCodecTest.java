package vingo.search.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vingo.search.InvalidSearchRequestException;
import vingo.search.MatchMode;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec();

    @Test
    void decode_returnsSamePayload_afterEncode() {
        UUID id = UUID.randomUUID();

        var payload = codec.decode(codec.encode("price", "500000", id, "sig", MatchMode.STRICT));

        assertThat(payload.s()).isEqualTo("price");
        assertThat(payload.k()).isEqualTo("500000");
        assertThat(payload.i()).isEqualTo(id);
        assertThat(codec.matches(payload, "price", "sig")).isTrue();
    }

    @Test
    void matches_returnsFalse_whenSortOrFilterDiffers() {
        var payload = codec.decode(codec.encode("price", "1", UUID.randomUUID(), "sig", MatchMode.STRICT));

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

    @Test
    void decode_keepsMatchMode_afterEncode() {
        var payload = codec.decode(codec.encode("relevance", "0.5", UUID.randomUUID(), "sig", MatchMode.LOOSE));

        assertThat(payload.matchMode()).isEqualTo(MatchMode.LOOSE);
    }

    @Test
    void decode_treatsMissingModeAsStrict() {
        String old = "{\"s\":\"price\",\"k\":\"1\",\"i\":\"" + UUID.randomUUID() + "\",\"f\":\"abc\"}";
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(old.getBytes());

        assertThat(codec.decode(cursor).matchMode()).isEqualTo(MatchMode.STRICT);
    }

    @Test
    void decode_throwsValidationError_whenModeIsInvalid() {
        String json = "{\"s\":\"price\",\"k\":\"1\",\"i\":\"" + UUID.randomUUID() + "\",\"f\":\"abc\",\"m\":\"FUZZY\"}";
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());

        assertThatThrownBy(() -> codec.decode(cursor)).isInstanceOf(InvalidSearchRequestException.class);
    }
}
