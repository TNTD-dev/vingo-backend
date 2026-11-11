package vingo.notifications.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vingo.notifications.NotificationsDeliveryService;

@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxRepository outbox;
    private final EmailSender sender;
    private final NotificationsDeliveryService delivery;
    private final MeterRegistry metrics;

    public OutboxDispatcher(OutboxRepository outbox, EmailSender sender,
            NotificationsDeliveryService delivery, MeterRegistry metrics) {
        this.outbox = outbox;
        this.sender = sender;
        this.delivery = delivery;
        this.metrics = metrics;
    }

    /**
     * Claim và cập nhật kết quả trong transaction ngắn qua delivery service;
     * giải mã/kết xuất và gọi provider ở ngoài transaction. Lỗi cấu hình hoặc
     * payload chỉ tạm hoãn, còn xung đột idempotency cần đối soát thủ công.
     *
     * @return true nếu đã nhận một thư, false nếu không còn thư đến hạn
     */
    @Transactional(propagation = Propagation.NEVER)
    public boolean dispatchOne() {
        var claim = delivery.claim();
        if (claim.isEmpty()) return false;
        var message = claim.get();
        try {
            sender.validateConfiguration();
            var prepared = delivery.prepare(message, outbox.decode(message));
            if (prepared.isEmpty()) return true;
            EmailPayload payload = prepared.get();
            var renewed = delivery.startSend(message);
            if (renewed.isEmpty()) return true;
            message = renewed.get();
            sender.send(message.id(), payload);
            boolean completed = delivery.complete(message);
            metrics.counter("vingo.notifications.delivery", "outcome", completed ? "accepted" : "lease_lost").increment();
            log.atInfo().addKeyValue("outbox_id", message.id()).log(completed ? "Notification accepted by provider" : "Notification lease lost");
        } catch (PayloadUnavailableException exception) {
            delivery.pause(message, "PAYLOAD_UNAVAILABLE");
            log.atWarn().addKeyValue("outbox_id", message.id()).addKeyValue("error_code", "PAYLOAD_UNAVAILABLE")
                    .log("Notification paused; payload preserved");
        } catch (DeliveryException exception) {
            if (requiresConfigurationRepair(exception.code())) {
                delivery.pause(message, exception.code());
            } else {
                delivery.fail(message, exception.retryable(), exception.code(), exception.retryAfterSeconds());
            }
            metrics.counter("vingo.notifications.delivery", "outcome", "error").increment();
            log.atWarn().addKeyValue("outbox_id", message.id()).addKeyValue("error_code", exception.code())
                    .addKeyValue("retry_after_seconds", exception.retryAfterSeconds())
                    .log("Notification delivery failed");
            if (exception.code().equals("EMAIL_IDEMPOTENCY_CONFLICT")) {
                // Không đổi key để gửi lại: provider có thể đã nhận thư trước đó.
                metrics.counter("vingo.notifications.delivery", "outcome", "reconciliation_required").increment();
                log.atError().addKeyValue("outbox_id", message.id())
                        .addKeyValue("error_code", exception.code())
                        .log("Notification requires provider reconciliation; message preserved without automatic resend");
            }
        } catch (RuntimeException exception) {
            // Giữ vị trí stack cho Sentry, bỏ message/cause có thể chứa dữ liệu nhạy cảm.
            var safe = new IllegalStateException("Unexpected notification failure: " + exception.getClass().getName());
            safe.setStackTrace(exception.getStackTrace());
            Sentry.captureException(safe);
            delivery.pause(message, "UNEXPECTED_DELIVERY_ERROR");
            log.atError().addKeyValue("outbox_id", message.id()).setCause(safe).log("Notification paused after unexpected failure");
        }
        return true;
    }

    /** Lỗi cấu hình có thể phục hồi: giữ PENDING/payload và không tiêu hao attempts. */
    private static boolean requiresConfigurationRepair(String code) {
        return code.equals("EMAIL_NOT_CONFIGURED") || code.equals("EMAIL_HTTP_401") || code.equals("EMAIL_HTTP_403");
    }
}
