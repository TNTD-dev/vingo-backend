package vingo.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
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
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(UUID userId, String endpoint, UUID key, Object request, Class<T> responseType,
                         int responseStatus, Supplier<T> action) {
        String requestHash = hash(endpoint + jsonMapper.writeValueAsString(request));
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Giới hạn 3 lần thử để không lặp mãi khi key liên tục bị xoá hoặc tạo lại.
        for (int attempt = 0; attempt < 3; attempt++) {
            var inserted = jdbc.sql("""
                            INSERT INTO idempotency_key (user_id, endpoint, key, request_hash, created_at)
                            VALUES (:user, :endpoint, :key, :hash, :now)
                            ON CONFLICT (user_id, endpoint, key) DO NOTHING RETURNING id
                            """)
                    .param("user", userId).param("endpoint", endpoint).param("key", key.toString())
                    .param("hash", requestHash).param("now", now).query(UUID.class).optional();

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
                            SELECT id, created_at, request_hash, response FROM idempotency_key
                            WHERE user_id = :user AND endpoint = :endpoint AND key = :key
                            FOR UPDATE
                            """)
                    .param("user", userId).param("endpoint", endpoint).param("key", key.toString())
                    .query((rs, row) -> new StoredResponse(rs.getObject("id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getString("request_hash"), rs.getString("response")))
                    .optional();
            // Job dọn hoặc request khác có thể đã xoá key sau lần chèn bị trùng.
            if (previous.isEmpty()) {
                continue;
            }
            var stored = previous.get();
            if (!stored.createdAt().isAfter(now.minusHours(24))) {
                jdbc.sql("DELETE FROM idempotency_key WHERE id = :id").param("id", stored.id()).update();
                continue;
            }
            if (!requestHash.equals(stored.requestHash())) {
                throw new DomainException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key đã dùng với nội dung khác");
            }
            // Response NULL chỉ có khi dữ liệu sai vì key và kết quả luôn commit cùng transaction.
            if (stored.response() == null) {
                throw new DomainException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key chưa có kết quả hoàn chỉnh");
            }
            return jsonMapper.readValue(stored.response(), responseType);
        }
        throw new IllegalStateException("Idempotency-Key tranh chấp liên tục: " + key);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 không khả dụng", ex);
        }
    }

    private record StoredResponse(UUID id, OffsetDateTime createdAt, String requestHash, String response) {}
}
