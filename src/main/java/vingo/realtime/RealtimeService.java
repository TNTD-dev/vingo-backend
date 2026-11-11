package vingo.realtime;

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

    public RealtimeService(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // Event phải nằm cùng transaction nghiệp vụ để rollback cùng thay đổi dữ liệu.
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(UUID userId, RealtimeEventType type, Map<String, Object> payload) {
        UUID id = jdbc.sql("""
                        INSERT INTO realtime_event (user_id, type, payload)
                        VALUES (:userId, :type, CAST(:payload AS jsonb)) RETURNING id
                        """)
                .param("userId", userId)
                .param("type", type.dbValue())
                .param("payload", jsonMapper.writeValueAsString(payload))
                .query(UUID.class).single();

        // NOTIFY giới hạn 8000 byte nên chỉ gửi id để listener đọc lại sự kiện theo id.
        jdbc.sql("SELECT pg_notify('realtime_event', :id)")
                .param("id", id.toString()).query().singleRow();
        return id;
    }
}
