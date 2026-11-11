package vingo.notifications.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OutboxRepository {
    public record Pending(UUID id, String recipient, String template, String payload, int attempts, OffsetDateTime retryAt) {
        @Override public String toString() { return "Pending[id=" + id + ",redacted]"; }
    }
    private final JdbcClient jdbc;
    private final PayloadCipher legacyCipher;
    private final TicketEmailRenderer renderer;
    private final JsonMapper json = JsonMapper.builder().build();

    public OutboxRepository(JdbcClient jdbc, PayloadCipher legacyCipher, TicketEmailRenderer renderer) {
        this.jdbc = jdbc;
        this.legacyCipher = legacyCipher;
        this.renderer = renderer;
    }

    public UUID enqueueTickets(String recipient, List<UUID> ticketIds) {
        return jdbc.sql("""
                INSERT INTO outbox_message(channel,recipient,template,payload)
                VALUES ('EMAIL',:recipient,'TICKETS',CAST(:payload AS jsonb)) RETURNING id
                """).param("recipient", recipient)
                .param("payload", json.writeValueAsString(Map.of("ticket_ids", ticketIds)))
                .query(UUID.class).single();
    }

    /** Đặt thời điểm thử lại dự phòng bằng send_after, không thêm trạng thái/cột. */
    public Optional<Pending> claim() {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM outbox_message WHERE channel='EMAIL' AND status='PENDING'
                        AND template IN ('OTP','TICKETS') AND send_after<=now() AND attempts<5
                    ORDER BY send_after,id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE outbox_message o SET send_after=now()+interval '30 seconds'
                FROM candidate c WHERE o.id=c.id
                RETURNING o.id,o.recipient,o.template,o.payload::text,o.attempts,o.send_after
                """).query((rs,row) -> new Pending(rs.getObject("id",UUID.class),rs.getString("recipient"),
                        rs.getString("template"),rs.getString("payload"),rs.getInt("attempts"),
                        rs.getObject("send_after",OffsetDateTime.class))).optional();
    }

    /**
     * Đọc ciphertext legacy hoặc dựng email mới từ ticket_ids, không ghi thêm
     * metadata vào payload. Nội dung mới đọc từ Catalog hiện tại nên chưa có
     * bảo đảm bất biến giữa các lần retry; thiết kế khắc phục còn chờ team duyệt.
     */
    public EmailPayload decode(Pending message) {
        var tree = json.readTree(message.payload());
        // Chỉ đọc tương thích thư bản cũ, không ghi envelope/metadata mới.
        if (tree.path("version").asInt() == 1 && tree.has("ciphertext")) {
            return json.readValue(legacyCipher.decrypt(tree.path("ciphertext").asText()),EmailPayload.class);
        }
        if (!message.template().equals("TICKETS") || !tree.path("ticket_ids").isArray()) {
            throw new PayloadUnavailableException();
        }
        var ids = new java.util.ArrayList<UUID>();
        for (var node : tree.path("ticket_ids")) ids.add(UUID.fromString(node.asText()));
        return renderer.render(message.recipient(),ids);
    }

    /** Chỉ ghi SENT nếu row vẫn thuộc lần claim này (id, PENDING và send_after khớp). */
    public boolean complete(Pending message) {
        return jdbc.sql("""
                UPDATE outbox_message SET status='SENT',sent_at=now(),last_error=NULL
                WHERE id=:id AND status='PENDING' AND send_after=:expected
                """).param("id",message.id()).param("expected",message.retryAt()).update()==1;
    }

    /**
     * Lỗi gửi tiêu hao một attempt; retry tối đa năm lỗi, còn lỗi vĩnh viễn
     * chuyển FAILED ngay. Giữ nguyên nội dung, tôn trọng Retry-After và chỉ
     * cập nhật lần claim còn hiệu lực. FAILED không tự được gửi lại.
     */
    public void fail(Pending message, boolean retryable, String code, int retryAfterSeconds) {
        int delay = Math.max(5 * (1 << Math.min(message.attempts(),4)),Math.max(0,retryAfterSeconds));
        jdbc.sql("""
                UPDATE outbox_message SET attempts=attempts+1,
                    status=CASE WHEN :retry AND attempts+1<5 THEN 'PENDING' ELSE 'FAILED' END,
                    send_after=now()+make_interval(secs=>:delay),last_error=:code
                WHERE id=:id AND status='PENDING' AND send_after=:expected
                """).param("retry",retryable).param("delay",delay).param("code",code)
                .param("id",message.id()).param("expected",message.retryAt()).update();
    }

    /** Lùi 60 giây cho lần claim còn hiệu lực, không xoá payload hoặc tăng attempts. */
    public void pause(Pending message, String code) {
        jdbc.sql("""
                UPDATE outbox_message SET send_after=now()+interval '60 seconds',last_error=:code
                WHERE id=:id AND status='PENDING' AND send_after=:expected
                """).param("code",code).param("id",message.id()).param("expected",message.retryAt()).update();
    }
}
