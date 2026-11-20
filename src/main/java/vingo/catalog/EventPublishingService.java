package vingo.catalog;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.catalog.internal.EventPublishingRepository;
import vingo.catalog.internal.EventRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
public class EventPublishingService {

    private final EventRepository events;
    private final EventPublishingRepository publishing;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public EventPublishingService(
            EventRepository events,
            EventPublishingRepository publishing,
            ApplicationEventPublisher publisher,
            Clock clock) {
        this.events = events;
        this.publishing = publishing;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void publish(UUID id) {
        var event = events.lockById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện."));
        if ("PUBLISHED".equals(event.status())) {
            return;
        }
        if (!"DRAFT".equals(event.status())) {
            throw new DomainException(ErrorCode.EVENT_LOCKED, "Sự kiện đã hủy.");
        }
        var sessions = publishing.lockShowtimes(id);
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        if (!publishing.complete(id, now)) {
            throw new DomainException(ErrorCode.EVENT_INCOMPLETE,
                    "Thiếu suất hợp lệ (chưa qua giờ đóng bán), sơ đồ hoặc giá cho mọi khu.");
        }
        publishing.publish(id, now);
        publisher.publishEvent(new CatalogChanged(id, Set.copyOf(sessions)));
        try (var ignored = MDC.putCloseable("event_id", id.toString())) {
            LoggerFactory.getLogger(EventPublishingService.class)
                    .info("Đã công bố sự kiện showtime_count={}", sessions.size());
        }
    }

    @Transactional
    public void cancelEvent(UUID eventId, UUID actor, String reason) {
        validateReason(reason);
        var event = events.lockById(eventId).orElseThrow(EventPublishingService::missing);
        if ("CANCELLED".equals(event.status())) {
            return;
        }
        var sessions = publishing.cancellationStates(eventId);
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        boolean allFinished = !sessions.isEmpty() && sessions.stream()
                .allMatch(s -> s.cancelledAt() != null || !now.isBefore(s.endsAt()));
        if (allFinished || sessions.stream().anyMatch(s -> blocksCancellation(event.status(), s, now))) {
            throw new DomainException(ErrorCode.EVENT_LOCKED, "Sự kiện đã mở bán, có giao dịch hoặc đã kết thúc.");
        }
        sessions.stream().filter(s -> s.cancelledAt() == null).forEach(s -> publishing.cancelShowtime(s.id(), now));
        publishing.cancelEvent(eventId, now);
        publishing.auditCancelEvent(actor, eventId, reason.strip(), event.status(), now);
        publisher.publishEvent(new CatalogChanged(eventId, sessions.stream()
                .map(EventPublishingRepository.CancellationState::id)
                .collect(java.util.stream.Collectors.toSet())));
    }

    @Transactional
    public void cancelShowtime(UUID showtimeId, UUID actor, String reason) {
        validateReason(reason);
        UUID eventId = publishing.findShowtimeEvent(showtimeId).orElseThrow(EventPublishingService::missing);
        var event = events.lockById(eventId).orElseThrow(EventPublishingService::missing);
        var sessions = publishing.cancellationStates(eventId);
        var target = sessions.stream().filter(s -> s.id().equals(showtimeId)).findFirst()
                .orElseThrow(EventPublishingService::missing);
        if (target.cancelledAt() != null) {
            return;
        }
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        long active = sessions.stream().filter(s -> s.cancelledAt() == null).count();
        if (!Set.of("DRAFT", "PUBLISHED").contains(event.status())
                || blocksCancellation(event.status(), target, now)
                || ("PUBLISHED".equals(event.status()) && active <= 1)) {
            throw new DomainException(ErrorCode.SHOWTIME_LOCKED,
                    "Không thể hủy suất đã mở bán, có giao dịch hoặc là suất cuối của sự kiện đã công bố.");
        }
        publishing.cancelShowtime(showtimeId, now);
        publishing.auditCancelShowtime(actor, showtimeId, reason.strip(), now);
        publisher.publishEvent(new CatalogChanged(eventId, Set.of(showtimeId)));
    }

    private static boolean blocksCancellation(
            String eventStatus,
            EventPublishingRepository.CancellationState state,
            OffsetDateTime now) {
        return state.hasOrders() || ("PUBLISHED".equals(eventStatus) && !now.isBefore(state.saleOpensAt()));
    }

    private static DomainException missing() {
        return new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện hoặc suất diễn.");
    }

    private static void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Nhập lý do hủy từ 1 đến 500 ký tự.");
        }
    }
}
