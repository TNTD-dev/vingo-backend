package vingo.notifications.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record EmailPayload(String recipient, String subject, String html, List<Attachment> attachments, String sender) {
    public EmailPayload(String recipient, String subject, String html, List<Attachment> attachments) {
        this(recipient, subject, html, attachments, null);
    }

    /**
     * Tệp đính kèm gửi cho Resend, nội dung base64. Có {@code contentId} thì là ảnh nhúng,
     * HTML tham chiếu bằng {@code cid:<contentId>}. Thư cũ trong outbox không có hai trường mới
     * nên đọc lại vẫn được, và trường null không được gửi lên provider.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attachment(
            String filename,
            String content,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("content_id") String contentId) {
        public Attachment(String filename, String content) {
            this(filename, content, null, null);
        }
        @Override public String toString() { return "Attachment[redacted]"; }
    }
    @Override public String toString() { return "EmailPayload[redacted]"; }
}
