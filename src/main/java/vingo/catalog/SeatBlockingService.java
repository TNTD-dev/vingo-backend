package vingo.catalog;

import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vingo.catalog.internal.EventRepository;
import vingo.catalog.internal.SeatBlockingRepository;
import vingo.shared.error.*;
import vingo.shared.logging.LogKeys;

@Service
public class SeatBlockingService {
    private static final Logger log=LoggerFactory.getLogger(SeatBlockingService.class);
    private final EventRepository events;
    private final SeatBlockingRepository seats;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    public SeatBlockingService(EventRepository events,SeatBlockingRepository seats,ApplicationEventPublisher publisher,Clock clock) {
        this.events=events; this.seats=seats; this.publisher=publisher; this.clock=clock;
    }
    @Transactional
    public void block(UUID id,List<UUID> ids,String reason,UUID actorId) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw error(ErrorCode.VALIDATION_ERROR,"Lý do khóa không hợp lệ.");
        change(id,ids,true,reason.strip(),actorId);
    }
    @Transactional
    public void unblock(UUID id,List<UUID> ids,UUID actorId) { change(id,ids,false,null,actorId); }
    private void change(UUID id,List<UUID> ids,boolean block,String reason,UUID actorId) {
        if(ids==null || ids.isEmpty() || ids.size()>200 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size())
            throw error(ErrorCode.VALIDATION_ERROR,"Danh sách ghế không hợp lệ.");
        UUID eventId=seats.eventId(id).orElseThrow(()->error(ErrorCode.NOT_FOUND,"Không tìm thấy suất."));
        var event=events.lockById(eventId).orElseThrow(()->error(ErrorCode.NOT_FOUND,"Không tìm thấy sự kiện."));
        if(!Set.of("DRAFT","PUBLISHED").contains(event.status())) throw error(ErrorCode.EVENT_LOCKED,"Sự kiện đã kết thúc hoặc hủy.");
        var session=seats.lockSession(id).orElseThrow(()->error(ErrorCode.NOT_FOUND,"Không tìm thấy suất."));
        var now=clock.instant().atOffset(ZoneOffset.UTC);
        if(session.status().equals("CANCELLED") || !now.isBefore(session.endsAt())) throw error(ErrorCode.SHOWTIME_LOCKED,"Suất đã kết thúc hoặc hủy.");
        var current=seats.lockSeats(id,session.layoutId(),ids);
        if(current.size()!=ids.size()) throw error(ErrorCode.VALIDATION_ERROR,"Ghế không thuộc sơ đồ hoặc thiếu tồn kho.");
        for(var seat:current) {
            boolean free=Set.of("AVAILABLE","BLOCKED").contains(seat.status());
            boolean expired=block && seat.status().equals("HELD") && seat.heldUntil()!=null && seat.heldUntil().isBefore(now);
            if(!free && !expired) throw error(ErrorCode.SEAT_TAKEN,"Có ghế đang giữ hoặc đã bán; danh sách chưa đổi.");
        }
        String target=block?"BLOCKED":"AVAILABLE";
        var changed=current.stream().filter(s->!target.equals(s.status())).map(SeatBlockingRepository.SeatState::id).toList();
        if(changed.isEmpty()) return;
        seats.update(id,changed,block,reason,now);
        publisher.publishEvent(new CatalogChanged(eventId,Set.of(id)));
        try(var e=MDC.putCloseable("event_id",eventId.toString());var s=MDC.putCloseable("showtime_id",id.toString())) {
            log.atInfo().addKeyValue(LogKeys.USER_ID, actorId)
                    .log("Đã cập nhật khóa ghế action={} count={}",block?"BLOCK":"UNBLOCK",changed.size());
        }
    }
    private static DomainException error(ErrorCode code,String detail) { return new DomainException(code,detail); }
}
