package vingo.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Chạy cùng transaction với use case, chỉ lưu key và kết quả khi thành công. */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public IdempotencyService(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // shortcut: key không hết hạn, cần job dọn idempotency_key > 24h.
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(UUID userId, String endpoint, UUID key, Object request, Class<T> responseType,
                         int responseStatus, Supplier<T> action) {
        String requestHash = hash(endpoint + jsonMapper.writeValueAsString(request));
        var inserted = jdbc.sql("""
                        INSERT INTO idempotency_key (user_id, endpoint, key, request_hash)
                        VALUES (:user, :endpoint, :key, :hash)
                        ON CONFLICT (user_id, endpoint, key) DO NOTHING RETURNING id
                        """)
                .param("user", userId).param("endpoint", endpoint).param("key", key.toString())
                .param("hash", requestHash).query(UUID.class).optional();

        if (inserted.isPresent()) {
            T result = action.get();
            jdbc.sql("""
                            UPDATE idempotency_key SET response_status = :status, response = CAST(:response AS jsonb)
                            WHERE id = :id
                            """)
                    .param("status", responseStatus).param("response", jsonMapper.writeValueAsString(result))
                    .param("id", inserted.get()).update();
            return result;
        }

        var previous = jdbc.sql("""
                        SELECT request_hash, response FROM idempotency_key
                        WHERE user_id = :user AND endpoint = :endpoint AND key = :key
                        """)
                .param("user", userId).param("endpoint", endpoint).param("key", key.toString())
                .query((rs, row) -> new StoredResponse(rs.getString("request_hash"), rs.getString("response")))
                .single();
        if (!requestHash.equals(previous.requestHash())) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key đã dùng với nội dung khác");
        }
        // Response NULL chỉ có khi dữ liệu sai vì key và kết quả luôn commit cùng transaction.
        if (previous.response() == null) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key chưa có kết quả hoàn chỉnh");
        }
        return jsonMapper.readValue(previous.response(), responseType);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 không khả dụng", ex);
        }
    }

    private record StoredResponse(String requestHash, String response) {}
}
