package vingo.notifications.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Đọc mail config chung. MAIL_PAYLOAD_KEY chỉ phục hồi payload bản cũ, không cần cho email mới. */
@Component
public record NotificationSettings(String encryptionKey, String sender, String apiKey, String baseUrl) {
    public NotificationSettings(
            @Value("${MAIL_PAYLOAD_KEY:}") String encryptionKey,
            @Value("${vingo.mail.from}") String sender,
            @Value("${vingo.mail.api-key}") String apiKey,
            @Value("${vingo.mail.base-url:https://api.resend.com}") String baseUrl) {
        this.encryptionKey = encryptionKey;
        this.sender = sender;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override public String toString() { return "NotificationSettings[redacted]"; }
}
