package vingo.search.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.catalog.ShowtimeStatus;
import vingo.search.EventDetailResult;
import vingo.search.EventSearchCriteria;
import vingo.search.EventSummaryResult;
import vingo.search.MatchMode;
import vingo.search.Page;
import vingo.search.ShowtimeResult;
import vingo.search.SearchService;
import vingo.search.internal.EventDetailRepository.EventRow;
import vingo.search.internal.EventDetailRepository.ShowtimeRow;
import vingo.search.internal.SearchQueryNormalizer.NormalizedQuery;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Hiện thực {@link SearchService} bằng SQL chỉ đọc. Giờ lấy từ {@link Clock}, truyền vào SQL. */
@Service
@Transactional(readOnly = true)
class PgSearchService implements SearchService {

    private final EventSearchRepository events;
    private final EventDetailRepository details;
    private final SearchQueryNormalizer normalizer;
    private final Clock clock;

    PgSearchService(EventSearchRepository events, EventDetailRepository details, SearchQueryNormalizer normalizer,
            Clock clock) {
        this.events = events;
        this.details = details;
        this.normalizer = normalizer;
        this.clock = clock;
    }

    @Override
    public Page<EventSummaryResult> searchEvents(EventSearchCriteria criteria) {
        NormalizedQuery query = null;
        if (criteria.q() != null) {
            Optional<NormalizedQuery> normalized = normalizer.normalize(criteria.q());
            if (normalized.isEmpty()) {
                // Từ khoá toàn ký tự đặc biệt: không có gì để khớp.
                return Page.empty();
            }
            query = normalized.get();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Trang sau dùng đúng chế độ ghi trong cursor; trang đầu bắt đầu bằng khớp chặt.
        MatchMode mode = criteria.after() == null ? MatchMode.STRICT : criteria.after().matchMode();
        // Lấy dư một dòng để biết còn trang sau.
        List<EventSummaryResult> rows = events.search(criteria, query, mode, now, criteria.limit() + 1);
        // Khớp chặt không ra gì ở trang đầu thì nới sang khớp lỏng (từ thừa như "concert", "vé").
        if (rows.isEmpty() && criteria.after() == null && query != null && query.canLoosen()) {
            mode = MatchMode.LOOSE;
            rows = events.search(criteria, query, mode, now, criteria.limit() + 1);
        }
        boolean hasMore = rows.size() > criteria.limit();
        return new Page<>(hasMore ? rows.subList(0, criteria.limit()) : rows, hasMore, mode);
    }

    @Override
    public EventDetailResult getEvent(UUID eventId) {
        EventRow event = details.findPublished(eventId).orElseThrow(PgSearchService::eventNotFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ShowtimeRow> rows = details.findShowtimes(eventId, now);
        // Địa điểm của suất sắp tới sớm nhất; không còn suất sắp tới thì của suất đầu tiên.
        Optional<ShowtimeRow> featured = rows.stream().filter(r -> !r.startsAt().isBefore(now)).findFirst()
                .or(() -> rows.stream().findFirst());
        return new EventDetailResult(
                event.id(), event.title(), event.description(), event.category(), event.status(), event.posterUrl(),
                event.attributes(), event.maxTicketsPerOrder(),
                featured.map(ShowtimeRow::venue),
                details.findPriceTiers(eventId),
                rows.stream().map(r -> toShowtime(r, now)).toList());
    }

    @Override
    public List<ShowtimeResult> listEventShowtimes(UUID eventId) {
        details.findPublished(eventId).orElseThrow(PgSearchService::eventNotFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return details.findShowtimes(eventId, now).stream().map(r -> toShowtime(r, now)).toList();
    }

    private static ShowtimeResult toShowtime(ShowtimeRow r, OffsetDateTime now) {
        ShowtimeStatus status = ShowtimeStatus.of(r.storedStatus(), r.saleOpensAt(), r.saleClosesAt(), r.endsAt(), now);
        return new ShowtimeResult(r.id(), r.eventId(), r.hallId(), r.seatLayoutId(), r.startsAt(), r.endsAt(),
                r.saleOpensAt(), r.saleClosesAt(), status, r.soldOut());
    }

    private static DomainException eventNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "Sự kiện không tồn tại");
    }
}
