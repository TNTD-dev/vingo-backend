package vingo.notifications;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vingo.notifications.internal.OutboxRepository;

/** Use case DB của worker; commit trước/sau HTTP tại dispatcher. */
@Service
public class NotificationsDeliveryService {
    private final OutboxRepository outbox;
    public NotificationsDeliveryService(OutboxRepository outbox) { this.outbox = outbox; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public Optional<OutboxRepository.Pending> claim() {
        return outbox.claim();
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
