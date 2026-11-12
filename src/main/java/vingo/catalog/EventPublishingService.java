package vingo.catalog;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vingo.catalog.internal.EventRepository;
import vingo.catalog.internal.EventPublishingRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
public class EventPublishingService {
    private final EventRepository events;
    private final EventPublishingRepository publishing;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    public EventPublishingService(EventRepository events,EventPublishingRepository publishing,ApplicationEventPublisher publisher,Clock clock) {
        this.events=events;this.publishing=publishing;this.publisher=publisher;this.clock=clock;
    }
    @Transactional
    public void publish(UUID id) {
        var event=events.lockById(id).orElseThrow(()->new DomainException(ErrorCode.NOT_FOUND,"Không tìm thấy sự kiện."));
        if(event.status().equals("PUBLISHED")) return;
        if(!event.status().equals("DRAFT")) throw new DomainException(ErrorCode.EVENT_LOCKED,"Sự kiện đã kết thúc hoặc hủy.");
        var sessions=publishing.lockShowtimes(id);
        var now=clock.instant().atOffset(ZoneOffset.UTC);
        if(!publishing.complete(id,now)) throw new DomainException(ErrorCode.EVENT_INCOMPLETE,"Thiếu suất hợp lệ, sơ đồ đã công bố hoặc giá cho mọi khu.");
        publishing.publish(id,now);
        publisher.publishEvent(new CatalogChanged(id,Set.copyOf(sessions)));
        try(var ignored=MDC.putCloseable("event_id",id.toString())) {
            LoggerFactory.getLogger(EventPublishingService.class).info("Đã công bố sự kiện showtime_count={}",sessions.size());
        }
    }
}
