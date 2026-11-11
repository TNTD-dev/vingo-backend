package vingo.notifications;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vingo.notifications.internal.OutboxRepository;
import vingo.notifications.internal.EmailPayload;
import vingo.notifications.internal.NotificationSettings;

/** Use case DB của worker; commit trước/sau HTTP tại dispatcher. */
@Service
public class NotificationsDeliveryService {
    private final OutboxRepository outbox;
    private final NotificationSettings settings;
    public NotificationsDeliveryService(OutboxRepository outbox, NotificationSettings settings) { this.outbox = outbox; this.settings = settings; }

    /** Commit nội dung cố định trước khi dispatcher gửi HTTP; claim cũ không được gửi. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public Optional<EmailPayload> prepare(OutboxRepository.Pending message, EmailPayload candidate) {
        return outbox.prepare(message, candidate, settings.sender());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public Optional<OutboxRepository.Pending> claim() {
        return outbox.claim();
    }

    /** HTTP tối đa 10 giây và complete TX tối đa 8 giây nằm trong khoảng mới 30 giây. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public Optional<OutboxRepository.Pending> startSend(OutboxRepository.Pending message) {
        return outbox.startSend(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public boolean complete(OutboxRepository.Pending message) { return outbox.complete(message); }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public void fail(OutboxRepository.Pending message, boolean retryable, String code, int delay) {
        outbox.fail(message, retryable, code, delay);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public void pause(OutboxRepository.Pending message, String code) { outbox.pause(message, code); }
}
