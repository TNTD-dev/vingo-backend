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
    public record Pending(UUID id, String recipient, String template, String payload, int attempts, OffsetDateTime retryAt,
            boolean firstUncertainAttempt) {
        public Pending(UUID id, String recipient, String template, String payload, int attempts, OffsetDateTime retryAt) {
            this(id, recipient, template, payload, attempts, retryAt, false);
        }
        @Override public String toString() { return "Pending[id=" + id + ",redacted]"; }
    }
    private record SendState(String payload, OffsetDateTime observedAt) {
        @Override public String toString() { return "SendState[redacted]"; }
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
     * Gia hạn và ghi dấu trước HTTP trong cùng transaction. Lần gửi chưa rõ
     * kết quả được dừng trước hạn 24 giờ 30 giây để đủ ngân sách lần gửi sắp tới;
     * chuyển FAILED để đối soát, giữ nội dung và không đổi key.
     * Thư chưa thử gửi không bị giới hạn theo created_at.
     */
    public Optional<Pending> startSend(Pending message) {
        var current=jdbc.sql("SELECT payload::text,clock_timestamp() AS observed_at FROM outbox_message WHERE id=:id AND status='PENDING' AND send_after=:expected FOR UPDATE")
                .param("id",message.id()).param("expected",message.retryAt())
                .query((rs,row) -> new SendState(rs.getString("payload"),rs.getObject("observed_at",OffsetDateTime.class))).optional();
        if(current.isEmpty()) return Optional.empty();
        var tree=json.readTree(current.get().payload());
        boolean first=!tree.has("delivery_started_at");
        String started=first ? current.get().observedAt().toString() : tree.path("delivery_started_at").asText();
        boolean expired;
        try { expired=!OffsetDateTime.parse(started).plusHours(24).minusSeconds(30).isAfter(current.get().observedAt()); }
        catch(java.time.DateTimeException error) { expired=true; }
        if(expired) {
            jdbc.sql("UPDATE outbox_message SET status='FAILED',last_error='EMAIL_RECONCILIATION_REQUIRED' WHERE id=:id")
                    .param("id",message.id()).update();
            org.slf4j.LoggerFactory.getLogger(OutboxRepository.class).atWarn().addKeyValue("outbox_id",message.id())
                    .log("Safe provider retry window exhausted or unknown; message retained for reconciliation");
            return Optional.empty();
        }
        return jdbc.sql("""
                UPDATE outbox_message SET send_after=clock_timestamp()+interval '30 seconds',
                    payload=jsonb_set(payload,'{delivery_started_at}',CAST(:started AS jsonb))
                WHERE id=:id AND status='PENDING' AND send_after=:expected
                RETURNING id,recipient,template,payload::text,attempts,send_after
                """).param("id", message.id()).param("expected", message.retryAt()).param("started",json.writeValueAsString(started))
                .query((rs,row) -> new Pending(rs.getObject("id",UUID.class),rs.getString("recipient"),
                        rs.getString("template"),rs.getString("payload"),rs.getInt("attempts"),
                        rs.getObject("send_after",OffsetDateTime.class),first)).optional();
    }

    /**
     * Ưu tiên nội dung đã chốt; chỉ đọc Catalog cho thư chưa có bản nội dung gửi.
     * Ciphertext legacy vẫn cần đúng khóa và không được chuyển thành OTP plaintext.
     */
    public EmailPayload decode(Pending message) {
        var tree = json.readTree(message.payload());
        if (tree.has("delivery")) return json.readValue(tree.path("delivery").toString(), EmailPayload.class);
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

    /**
     * Chốt request trong payload có sẵn trước HTTP, dưới khóa row và điều kiện
     * claim. Nếu đã chốt thì dùng lại, kể cả caller cũ giữ candidate khác.
     * Thư legacy giữ ciphertext; chỉ chốt from, không lưu OTP đã giải mã.
     */
    public Optional<EmailPayload> prepare(Pending message, EmailPayload candidate, String sender) {
        var current = jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id AND status='PENDING' AND send_after=:expected FOR UPDATE")
                .param("id", message.id()).param("expected", message.retryAt()).query(String.class).optional();
        if (current.isEmpty()) return Optional.empty();
        var tree = json.readTree(current.get());
        if (tree.has("delivery")) return Optional.of(json.readValue(tree.path("delivery").toString(), EmailPayload.class));
        boolean legacy = tree.path("version").asInt() == 1 && tree.has("ciphertext");
        String fixedSender = tree.has("delivery_sender") ? tree.path("delivery_sender").asText() : sender;
        if (fixedSender == null || fixedSender.isBlank()) throw new DeliveryException(false, "EMAIL_NOT_CONFIGURED", 0);
        var fixed = new EmailPayload(candidate.recipient(), candidate.subject(), candidate.html(), candidate.attachments(), fixedSender);
        String key = legacy ? "delivery_sender" : "delivery";
        String value = json.writeValueAsString(legacy ? fixedSender : fixed);
        jdbc.sql("UPDATE outbox_message SET payload=jsonb_set(payload,ARRAY[:key],CAST(:value AS jsonb)) WHERE id=:id")
                .param("key", key).param("value", value).param("id", message.id()).update();
        return Optional.of(fixed);
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
                    send_after=now()+make_interval(secs=>:delay),last_error=:code,
                    payload=CASE WHEN :clear_started THEN payload-'delivery_started_at' ELSE payload END
                WHERE id=:id AND status='PENDING' AND send_after=:expected
                """).param("retry",retryable).param("delay",delay).param("code",code)
                .param("clear_started",message.firstUncertainAttempt() && definitelyRejected(code))
                .param("id",message.id()).param("expected",message.retryAt()).update();
    }

    /** Lùi 60 giây cho lần claim còn hiệu lực, không xoá payload hoặc tăng attempts. */
    public void pause(Pending message, String code) {
        jdbc.sql("""
                UPDATE outbox_message SET send_after=now()+interval '60 seconds',last_error=:code,
                    payload=CASE WHEN :clear_started THEN payload-'delivery_started_at' ELSE payload END
                WHERE id=:id AND status='PENDING' AND send_after=:expected
                """).param("code",code).param("id",message.id()).param("expected",message.retryAt())
                .param("clear_started",message.firstUncertainAttempt() && definitelyRejected(code)).update();
    }
    /** Chỉ bỏ dấu của lần đầu bị từ chối chắc chắn, không xoá rủi ro của lần gửi cũ. */
    private static boolean definitelyRejected(String code) {
        return java.util.Set.of("EMAIL_HTTP_400","EMAIL_HTTP_401","EMAIL_HTTP_403","EMAIL_HTTP_404",
                "EMAIL_HTTP_405","EMAIL_HTTP_413","EMAIL_HTTP_415","EMAIL_HTTP_422","EMAIL_HTTP_429").contains(code);
    }
}
