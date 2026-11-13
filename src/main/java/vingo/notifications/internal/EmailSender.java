package vingo.notifications.internal;

import java.util.UUID;

public interface EmailSender {
    /** Kiểm cấu hình trước khi ghi nhận một lần thử HTTP; không gọi mạng. */
    default void validateConfiguration() {}
    String send(UUID idempotencyKey, EmailPayload payload);
}
