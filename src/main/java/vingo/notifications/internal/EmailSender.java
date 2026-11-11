package vingo.notifications.internal;

import java.util.UUID;

public interface EmailSender {
    String send(UUID idempotencyKey, EmailPayload payload);
}
