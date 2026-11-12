package vingo.realtime;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** Ghi sự kiện realtime và báo cho listener trong transaction nghiệp vụ. */
@Service
public class RealtimeService {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** Nhận JDBC, bộ mã hoá JSON và đồng hồ dùng chung của nghiệp vụ. */
    public RealtimeService(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Ghi sự kiện vào {@code realtime_event} (created_at theo {@link Clock}) và gọi {@code pg_notify}
     * trên kênh cùng tên, chỉ gửi id để listener đọc lại payload. Phải có transaction nghiệp vụ
     * ({@code MANDATORY}); sự kiện rollback cùng dữ liệu và PostgreSQL chỉ chuyển NOTIFY sau commit.
     *
     * @param userId người dùng nhận sự kiện
     * @param type loại sự kiện với giá trị DB tương ứng
     * @param payload nội dung được lưu dưới dạng JSON, theo cấu trúc của từng loại sự kiện
     * @return id sự kiện vừa ghi; chưa đảm bảo transaction đã commit hay client đã nhận
     * @throws org.springframework.transaction.IllegalTransactionStateException nếu chưa có transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(UUID userId, RealtimeEventType type, Map<String, Object> payload) {
        UUID id = jdbc.sql("""
                        INSERT INTO realtime_event (user_id, type, payload, created_at)
                        VALUES (:userId, :type, CAST(:payload AS jsonb), :now) RETURNING id
                        """)
                .param("userId", userId)
                .param("type", type.dbValue())
                .param("payload", jsonMapper.writeValueAsString(payload))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .query(UUID.class).single();

        // NOTIFY giới hạn 8000 byte nên chỉ gửi id để listener đọc lại sự kiện theo id.
        jdbc.sql("SELECT pg_notify('realtime_event', :id)")
                .param("id", id.toString()).query().singleRow();
        return id;
    }
}
