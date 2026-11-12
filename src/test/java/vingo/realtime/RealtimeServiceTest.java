package vingo.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.support.IntegrationTest;

class RealtimeServiceTest extends IntegrationTest {

    private final RealtimeService realtime;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private UUID userId;

    @Autowired
    RealtimeServiceTest(RealtimeService realtime, DataSource dataSource,
                        PlatformTransactionManager transactionManager) {
        this.realtime = realtime;
        this.dataSource = dataSource;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void createUser() {
        userId = catalog.buyer();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void publish_usesInjectedClockForCreatedAt() {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        clock.set(now);

        UUID id = transactions.execute(status -> realtime.publish(userId, RealtimeEventType.ORDER_HELD, Map.of()));

        assertThat(jdbc.sql("SELECT created_at FROM realtime_event WHERE id = :id")
                .param("id", id).query(OffsetDateTime.class).single().toInstant()).isEqualTo(now);
    }

    @Test
    void publish_insertsEvent_whenCalledInTransaction() {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "order_id", orderId,
                "quantity", 2,
                "message", "Giữ vé",
                "seats", List.of("A1", "A2"),
                "details", Map.of("paid", false));

        UUID id = transactions.execute(status -> realtime.publish(userId, RealtimeEventType.ORDER_HELD, payload));

        assertThat(id).isNotNull();
        var event = jdbc.sql("""
                        SELECT user_id, type, jsonb_typeof(payload) AS payload_type,
                               payload = CAST(:expected AS jsonb) AS payload_matches
                        FROM realtime_event WHERE id = :id
                        """)
                .param("id", id)
                .param("expected", """
                        {"order_id":"%s","quantity":2,"message":"Giữ vé",
                         "seats":["A1","A2"],"details":{"paid":false}}
                        """.formatted(orderId))
                .query().singleRow();
        assertThat(event).containsEntry("user_id", userId)
                .containsEntry("type", "order.held")
                .containsEntry("payload_type", "object")
                .containsEntry("payload_matches", true);
    }

    @Test
    void publish_leavesNoEvent_whenTransactionRollsBack() {
        UUID id = transactions.execute(status -> {
            UUID eventId = realtime.publish(userId, RealtimeEventType.ORDER_HELD, Map.of());
            status.setRollbackOnly();
            return eventId;
        });

        assertThat(id).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM realtime_event WHERE id = :id")
                .param("id", id).query(Integer.class).single()).isZero();
    }

    @Test
    void publish_throws_whenNoTransaction() {
        assertThatThrownBy(() -> realtime.publish(userId, RealtimeEventType.ORDER_HELD, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM realtime_event WHERE user_id = :userId")
                .param("userId", userId).query(Integer.class).single()).isZero();
    }

    @Test
    void publish_notifiesListenersAfterCommit() throws SQLException {
        try (var listener = dataSource.getConnection(); var statement = listener.createStatement()) {
            listener.setAutoCommit(true);
            statement.execute("LISTEN realtime_event");
            try {
                var pgConnection = listener.unwrap(PGConnection.class);

                UUID id = transactions.execute(status -> {
                    UUID eventId = realtime.publish(userId, RealtimeEventType.ORDER_HELD,
                            Map.of("message", "x".repeat(9_000)));
                    assertThat(assertDoesNotThrow(() -> pgConnection.getNotifications(100))).isNullOrEmpty();
                    return eventId;
                });

                var notifications = pgConnection.getNotifications(5_000);
                assertThat(notifications).hasSize(1);
                assertThat(notifications[0].getName()).isEqualTo("realtime_event");
                assertThat(notifications[0].getParameter()).isEqualTo(id.toString());
            } finally {
                statement.execute("UNLISTEN realtime_event");
            }
        }
    }

    @Test
    void publish_acceptsEveryEventType() {
        transactions.executeWithoutResult(status -> {
            for (RealtimeEventType type : RealtimeEventType.values()) {
                UUID id = realtime.publish(userId, type, Map.of());

                assertThat(jdbc.sql("SELECT type FROM realtime_event WHERE id = :id")
                        .param("id", id).query(String.class).single()).isEqualTo(type.dbValue());
            }
        });
    }
}
