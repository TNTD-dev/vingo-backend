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

/**
 * Truy cập bảng payment_intent và payment_transaction bằng JdbcClient.
 */
@Repository
public class PaymentRepository {

    private static final String SELECT_INTENT = """
            SELECT pi.id, pi.order_id, pi.txn_ref, pi.amount, pi.status, pi.payment_url,
                   pi.created_at, pi.expires_at,
                   (SELECT pt.response_code FROM payment_transaction pt
                    WHERE pt.payment_intent_id = pi.id
                    ORDER BY pt.received_at DESC, pt.id DESC LIMIT 1) AS last_response_code
            FROM payment_intent pi
            """;

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IntentRow> findOpenIntentByOrderId(UUID orderId) {
        return jdbc.sql(SELECT_INTENT + " WHERE pi.order_id = :order AND pi.status = 'OPEN'")
                .param("order", orderId)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public Optional<IntentRow> findLatestIntentByOrderId(UUID orderId) {
        return jdbc.sql(SELECT_INTENT + " WHERE pi.order_id = :order ORDER BY pi.created_at DESC, pi.id DESC LIMIT 1")
                .param("order", orderId)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public int countIntentsByOrderId(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM payment_intent WHERE order_id = :order")
                .param("order", orderId)
                .query(Integer.class)
                .single();
    }

    public Optional<String> findOrderCode(UUID orderId) {
        return jdbc.sql("SELECT btrim(code) FROM orders WHERE id = :order")
                .param("order", orderId)
                .query(String.class)
                .optional();
    }

    public IntentRow insertIntent(UUID orderId, String txnRef, long amount, String paymentUrl,
            OffsetDateTime now, OffsetDateTime expiresAt) {
        return jdbc.sql("""
                        INSERT INTO payment_intent (order_id, txn_ref, amount, status, payment_url,
                                                    created_at, updated_at, expires_at)
                        VALUES (:order, :txnRef, :amount, 'OPEN', :url, :now, :now, :expires)
                        RETURNING id, order_id, txn_ref, amount, status, payment_url,
                                  created_at, expires_at, CAST(NULL AS text) AS last_response_code
                        """)
                .param("order", orderId)
                .param("txnRef", txnRef)
                .param("amount", amount)
                .param("url", paymentUrl)
                .param("now", now)
                .param("expires", expiresAt)
                .query(PaymentRepository::mapIntent)
                .single();
    }

    /**
     * Đọc không khoá order_id để khoá bảng orders trước rồi mới khoá
     * payment_intent (tránh deadlock).
     */
    public Optional<UUID> findOrderIdByTxnRef(String txnRef) {
        return jdbc.sql("SELECT order_id FROM payment_intent WHERE txn_ref = :txnRef")
                .param("txnRef", txnRef)
                .query(UUID.class)
                .optional();
    }

    public Optional<IntentRow> findIntentByTxnRef(String txnRef) {
        return jdbc.sql(SELECT_INTENT + " WHERE pi.txn_ref = :txnRef")
                .param("txnRef", txnRef)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public Optional<IntentRow> lockIntentByTxnRef(String txnRef) {
        return jdbc.sql("""
                        SELECT id, order_id, txn_ref, amount, status, payment_url, created_at, expires_at,
                               (SELECT pt.response_code FROM payment_transaction pt
                                WHERE pt.payment_intent_id = payment_intent.id
                                ORDER BY pt.received_at DESC, pt.id DESC LIMIT 1) AS last_response_code
                        FROM payment_intent
                        WHERE txn_ref = :txnRef
                        FOR UPDATE
                        """)
                .param("txnRef", txnRef)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public boolean hasOtherSucceededIntent(UUID orderId, UUID intentId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM payment_intent WHERE order_id = :order AND id <> :id AND status = 'SUCCEEDED')")
                .param("order", orderId).param("id", intentId).query(Boolean.class).single();
    }
    public void updateIntentStatus(UUID intentId, String status, OffsetDateTime now) {
        jdbc.sql("UPDATE payment_intent SET status = :status, updated_at = :now WHERE id = :id")
                .param("status", status)
                .param("now", now)
                .param("id", intentId)
                .update();
    }

    public void insertTransaction(UUID intentId, String source, String result, String responseCode,
            String gatewayTxnNo, Long amountVnd, String rawJson, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO payment_transaction (payment_intent_id, source, result, response_code,
                                                         gateway_txn_no, amount, raw, received_at)
                        VALUES (:intent, :source, :result, :code, :txnNo, :amount, CAST(:raw AS jsonb), :now)
                        ON CONFLICT (payment_intent_id) WHERE result = 'SUCCESS' DO NOTHING
                        """)
                .param("intent", intentId)
                .param("source", source)
                .param("result", result)
                .param("code", responseCode, Types.VARCHAR)
                .param("txnNo", gatewayTxnNo, Types.VARCHAR)
                .param("amount", amountVnd, Types.BIGINT)
                .param("raw", rawJson == null || rawJson.isBlank() ? "{}" : rawJson)
                .param("now", now)
                .update();
    }

    /**
     * Chọn các intent OPEN cần đối soát bằng FOR UPDATE SKIP LOCKED trong một
     * giao dịch ngắn.
     */
    public List<IntentRow> lockCandidatesForReconcile(OffsetDateTime createdBefore,
            OffsetDateTime expiredAfter, int limit, OffsetDateTime afterTime, UUID afterId) {
        return jdbc.sql("""
                        SELECT id, order_id, txn_ref, amount, status, payment_url, created_at, expires_at,
                               CAST(NULL AS text) AS last_response_code
                        FROM payment_intent
                        WHERE status = 'OPEN'
                          AND created_at <= :createdBefore
                          AND expires_at >= :expiredAfter
                        AND (CAST(:afterTime AS timestamptz) IS NULL OR (created_at, id) > (:afterTime, :afterId))
                        ORDER BY created_at ASC, id ASC
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
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
