package vingo.search.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;
import vingo.search.InvalidSearchRequestException;

/**
 * Mã hoá cursor: base64url của JSON {@code {s: sort, k: khoá cuối, i: id cuối, f: hash bộ lọc}}.
 *
 * <p>Cursor chỉ gắn với bộ lọc, không ký; khoá sai định dạng bị repository từ chối.
 */
@Component
class CursorCodec {

    record Payload(String s, String k, UUID i, String f) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();

    String encode(String sort, String key, UUID id, String filterSignature) {
        try {
            byte[] json = mapper.writeValueAsBytes(new Payload(sort, key, id, hash(filterSignature)));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Không mã hoá được cursor", e);
        }
    }

    /** Giải mã cursor; hỏng thì 400. */
    Payload decode(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            Payload payload = mapper.readValue(json, Payload.class);
            if (payload.s() == null || payload.k() == null || payload.i() == null || payload.f() == null) {
                throw new IllegalArgumentException("thiếu trường");
            }
            return payload;
        } catch (RuntimeException e) {
            throw new InvalidSearchRequestException("cursor", "cursor không hợp lệ");
        }
    }

    /** Cursor có sinh ra từ đúng sort và bộ lọc này không. */
    boolean matches(Payload payload, String sort, String filterSignature) {
        return payload.s().equals(sort) && payload.f().equals(hash(filterSignature));
    }

    static String hash(String signature) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
