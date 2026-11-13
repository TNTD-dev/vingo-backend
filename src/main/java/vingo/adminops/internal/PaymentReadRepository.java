package vingo.adminops.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.api.model.PaymentIntent;
import vingo.api.model.PaymentStatus;

/** Đọc payments cho admin bằng một query, không lấy URL thanh toán. */
@Repository
public class PaymentReadRepository {

    private final JdbcClient jdbc;

    /** Dùng JdbcClient để đọc trong transaction của AdminOpsService. */
    public PaymentReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Đọc intents theo created_at/id tăng dần cùng response_code mới nhất của từng intent. */
    public List<PaymentIntent> readPayments(UUID orderId) {
        // shortcut: đọc thẳng bảng payment, đổi sang PaymentService khi Luân có
        return jdbc.sql("""
                        SELECT p.id, p.order_id, p.txn_ref, p.amount, p.status, p.expires_at,
                               (SELECT t.response_code FROM payment_transaction t
                                WHERE t.payment_intent_id = p.id
                                ORDER BY t.received_at DESC, t.id DESC LIMIT 1) AS last_response_code
                        FROM payment_intent p WHERE p.order_id = :order ORDER BY p.created_at, p.id
                        """)
                .param("order", orderId).query((rs, row) -> new PaymentIntent()
                        .id(rs.getObject("id", UUID.class)).orderId(rs.getObject("order_id", UUID.class))
                        .txnRef(rs.getString("txn_ref")).amount(rs.getLong("amount"))
                        .status(PaymentStatus.fromValue(rs.getString("status")))
                        .expiresAt(rs.getObject("expires_at", OffsetDateTime.class))
                        .lastResponseCode(rs.getString("last_response_code"))).list();
    }
}
