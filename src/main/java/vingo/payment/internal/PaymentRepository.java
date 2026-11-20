package vingo.payment.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Truy cập bảng payment và payment_gateway_log bằng JdbcClient. */
@Repository
public class PaymentRepository {

    private static final String SELECT_INTENT = """
            SELECT p.id, p.order_id, p.txn_ref, o.total_amount AS amount, p.status::text AS status,
                   p.payment_url, p.created_at, o.expires_at,
                   (SELECT l.response_code FROM payment_gateway_log l
                    WHERE l.payment_id = p.id
                    ORDER BY l.received_at DESC, l.id DESC LIMIT 1) AS last_response_code
            FROM payment p
            JOIN ticket_order o ON o.id = p.order_id
            """;

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IntentRow> findOpenIntentByOrderId(UUID orderId) {
        return jdbc.sql(SELECT_INTENT + " WHERE p.order_id = :order AND p.status = 'OPEN'")
                .param("order", orderId)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public Optional<IntentRow> findLatestIntentByOrderId(UUID orderId) {
        return jdbc.sql(SELECT_INTENT + " WHERE p.order_id = :order ORDER BY p.created_at DESC, p.id DESC LIMIT 1")
                .param("order", orderId)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public int countIntentsByOrderId(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM payment WHERE order_id = :order")
                .param("order", orderId)
                .query(Integer.class)
                .single();
    }

    public Optional<String> findOrderCode(UUID orderId) {
        return jdbc.sql("SELECT btrim(code) FROM ticket_order WHERE id = :order")
                .param("order", orderId)
                .query(String.class)
                .optional();
    }

    public IntentRow insertIntent(UUID orderId, String txnRef, long amount, String paymentUrl,
            OffsetDateTime now, OffsetDateTime expiresAt) {
        UUID id = jdbc.sql("""
                        INSERT INTO payment (order_id, txn_ref, status, payment_url, created_at, updated_at)
                        VALUES (:order, :txnRef, 'OPEN', :url, :now, :now)
                        RETURNING id
                        """)
                .param("order", orderId)
                .param("txnRef", txnRef)
                .param("url", paymentUrl)
                .param("now", now)
                .query(UUID.class)
                .single();
        return new IntentRow(id, orderId, txnRef, amount, "OPEN", paymentUrl, now, expiresAt, null);
    }

    /** Đọc không khoá order_id để khoá ticket_order trước rồi mới khoá payment (tránh deadlock). */
    public Optional<UUID> findOrderIdByTxnRef(String txnRef) {
        return jdbc.sql("SELECT order_id FROM payment WHERE txn_ref = :txnRef")
                .param("txnRef", txnRef)
                .query(UUID.class)
                .optional();
    }

    public Optional<IntentRow> findIntentByTxnRef(String txnRef) {
        return jdbc.sql(SELECT_INTENT + " WHERE p.txn_ref = :txnRef")
                .param("txnRef", txnRef)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public Optional<IntentRow> lockIntentByTxnRef(String txnRef) {
        jdbc.sql("SELECT id FROM payment WHERE txn_ref = :txnRef FOR UPDATE")
                .param("txnRef", txnRef)
                .query(UUID.class)
                .optional();
        return jdbc.sql(SELECT_INTENT + " WHERE p.txn_ref = :txnRef")
                .param("txnRef", txnRef)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public boolean hasOtherSucceededIntent(UUID orderId, UUID intentId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM payment WHERE order_id = :order AND id <> :id AND status = 'SUCCEEDED')")
                .param("order", orderId)
                .param("id", intentId)
                .query(Boolean.class)
                .single();
    }

    public void updateIntentStatus(UUID intentId, String status, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET status = CAST(:status AS payment_status), updated_at = :now WHERE id = :id")
                .param("status", status)
                .param("now", now)
                .param("id", intentId)
                .update();
    }

    public void insertTransaction(UUID intentId, String source, String result, String responseCode,
            String gatewayTxnNo, Long amountVnd, String rawJson, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO payment_gateway_log (payment_id, source, result, response_code, raw, received_at)
                        VALUES (:payment, CAST(:source AS payment_source), CAST(:result AS payment_result),
                                :code, CAST(:raw AS jsonb), :now)
                        ON CONFLICT (payment_id) WHERE result = 'SUCCESS' DO NOTHING
                        """)
                .param("payment", intentId)
                .param("source", source)
                .param("result", result)
                .param("code", responseCode, Types.VARCHAR)
                .param("raw", rawJson == null || rawJson.isBlank() ? "{}" : rawJson)
                .param("now", now)
                .update();
    }

    public List<IntentRow> lockCandidatesForReconcile(OffsetDateTime createdBefore,
            OffsetDateTime expiredAfter, int limit, OffsetDateTime afterTime, UUID afterId) {
        return jdbc.sql("""
                        SELECT p.id, p.order_id, p.txn_ref, o.total_amount AS amount, p.status::text AS status,
                               p.payment_url, p.created_at, o.expires_at,
                               CAST(NULL AS text) AS last_response_code
                        FROM payment p
                        JOIN ticket_order o ON o.id = p.order_id
                        WHERE p.status = 'OPEN'
                          AND p.created_at <= :createdBefore
                          AND o.expires_at >= :expiredAfter
                          AND (CAST(:afterTime AS timestamptz) IS NULL OR (p.created_at, p.id) > (:afterTime, :afterId))
                        ORDER BY p.created_at ASC, p.id ASC
                        LIMIT :limit
                        FOR UPDATE OF p SKIP LOCKED
                        """)
                .param("afterTime", afterTime, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterId", afterId, Types.OTHER)
                .param("createdBefore", createdBefore)
                .param("expiredAfter", expiredAfter)
                .param("limit", limit)
                .query(PaymentRepository::mapIntent)
                .list();
    }

    private static IntentRow mapIntent(ResultSet rs, int rowNum) throws SQLException {
        return new IntentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("txn_ref"),
                rs.getLong("amount"),
                rs.getString("status"),
                rs.getString("payment_url"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("last_response_code")
        );
    }

    public record IntentRow(
            UUID id,
            UUID orderId,
            String txnRef,
            long amount,
            String status,
            String paymentUrl,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            String lastResponseCode
    ) {}
}
