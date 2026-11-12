package vingo.search.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.catalog.ShowtimeStatus;
import vingo.search.AvailabilityResult;
import vingo.search.EventDetailResult;
import vingo.search.EventSearchCriteria;
import vingo.search.EventSummaryResult;
import vingo.search.MatchMode;
import vingo.search.Page;
import vingo.search.SearchService;
import vingo.search.SeatResult;
import vingo.search.SeatState;
import vingo.search.SeatmapResult;
import vingo.search.SeatmapZoneResult;
import vingo.search.ShowtimeResult;
import vingo.search.ZoneKind;
import vingo.search.internal.EventDetailRepository.EventRow;
import vingo.search.internal.EventDetailRepository.ShowtimeRow;
import vingo.search.internal.SearchQueryNormalizer.NormalizedQuery;
import vingo.search.internal.SeatmapRepository.FreeSeat;
import vingo.search.internal.SeatmapRepository.LayoutSeat;
import vingo.search.internal.SeatmapRepository.LayoutZone;
import vingo.search.internal.SeatmapRepository.ShowtimeRef;
import vingo.search.internal.SeatmapRepository.StandingStock;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Hiện thực {@link SearchService} bằng SQL chỉ đọc. Giờ lấy từ {@link Clock}, truyền vào SQL. */
@Service
@Transactional(readOnly = true)
class PgSearchService implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(PgSearchService.class);

    private final EventSearchRepository events;
    private final EventDetailRepository details;
    private final SeatmapRepository seatmaps;
    private final SearchReads reads;
    private final SearchQueryNormalizer normalizer;
    private final Clock clock;

    PgSearchService(EventSearchRepository events, EventDetailRepository details, SeatmapRepository seatmaps,
            SearchReads reads, SearchQueryNormalizer normalizer, Clock clock) {
        this.events = events;
        this.details = details;
        this.seatmaps = seatmaps;
        this.reads = reads;
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
        EventCore core = reads.eventCore(eventId).orElseThrow(PgSearchService::eventNotFound);
        EventRow event = core.event();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ShowtimeRow> rows = core.showtimes();
        // Địa điểm của suất sắp tới sớm nhất; không còn suất sắp tới thì của suất đầu tiên.
        Optional<ShowtimeRow> featured = rows.stream().filter(r -> !r.startsAt().isBefore(now)).findFirst()
                .or(() -> rows.stream().findFirst());
        Set<UUID> soldOut = rows.isEmpty() ? Set.of() : details.findSoldOutShowtimeIds(eventId, now);
        return new EventDetailResult(
                event.id(), event.title(), event.description(), event.category(), event.status(), event.posterUrl(),
                event.attributes(), event.maxTicketsPerOrder(),
                featured.map(ShowtimeRow::venue),
                core.priceTiers(),
                rows.stream().map(r -> toShowtime(r, now, soldOut.contains(r.id()))).toList());
    }

    @Override
    public List<ShowtimeResult> listEventShowtimes(UUID eventId) {
        List<ShowtimeRow> rows = reads.showtimes(eventId).orElseThrow(PgSearchService::eventNotFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Set<UUID> soldOut = rows.isEmpty() ? Set.of() : details.findSoldOutShowtimeIds(eventId, now);
        return rows.stream().map(r -> toShowtime(r, now, soldOut.contains(r.id()))).toList();
    }

    @Override
    public SeatmapResult getSeatmap(UUID showtimeId, UUID zoneId) {
        ShowtimeRef showtime = seatmaps.findVisibleShowtime(showtimeId).orElseThrow(PgSearchService::showtimeNotFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Layout (khu, ghế, toạ độ, hạng giá) lấy từ cache; trạng thái ghế và chỗ đứng còn đọc DB mỗi lần.
        List<LayoutZone> zones = reads.layout(showtime).zones().stream()
                .filter(z -> zoneId == null || z.id().equals(zoneId))
                .toList();
        if (zoneId != null && zones.isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Khu không thuộc sơ đồ của suất");
        }
        Map<UUID, SeatState> states = seatmaps.findSeatStates(showtimeId, zoneId, now);
        Map<UUID, StandingStock> stock = seatmaps.findStandingStock(showtime);
        int missingSeats = 0;
        int missingStocks = 0;
        List<SeatmapZoneResult> result = new ArrayList<>();
        for (LayoutZone z : zones) {
            List<SeatResult> seats = new ArrayList<>(z.seats().size());
            for (LayoutSeat s : z.seats()) {
                SeatState state = states.get(s.id());
                if (state == null) {
                    // Thiếu dòng showtime_seat: báo BLOCKED, không cho đặt.
                    missingSeats++;
                    state = SeatState.BLOCKED;
                }
                seats.add(new SeatResult(s.id(), s.row(), s.number(), s.seatType(),
                        Optional.ofNullable(s.pairSeatId()), state, s.x(), s.y()));
            }
            StandingStock standing = z.kind() == ZoneKind.STANDING ? stock.get(z.id()) : null;
            if (standing != null && standing.missing()) {
                missingStocks++;
            }
            result.add(new SeatmapZoneResult(z.id(), z.name(), z.kind(), Optional.ofNullable(z.tier()),
                    standing == null ? 0 : standing.seatsLeft(), seats));
        }
        warnMissing(showtimeId, missingSeats, missingStocks);
        return new SeatmapResult(showtimeId, statusOf(showtime, now), result);
    }

    @Override
    public AvailabilityResult getAvailability(UUID showtimeId) {
        return reads.availability(showtimeId);
    }

    @Override
    public List<UUID> suggestSeats(UUID showtimeId, UUID zoneId, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n phải lớn hơn 0");
        }
        List<FreeSeat> free = seatmaps.findFreeSeats(showtimeId, zoneId, OffsetDateTime.now(clock));
        return SeatSuggester.pick(free, n);
    }

    /** Ghi WARN một lần cho mỗi request khi dữ liệu tồn kho của suất chưa đủ; không log từng ghế. */
    static void warnMissing(UUID showtimeId, int missingSeats, int missingStocks) {
        if (missingSeats > 0 || missingStocks > 0) {
            log.warn("Suất thiếu dữ liệu tồn kho, báo BLOCKED hoặc 0 chỗ showtime_id={} missing_seats={} missing_zone_stocks={}",
                    showtimeId, missingSeats, missingStocks);
        }
    }

    static ShowtimeStatus statusOf(ShowtimeRef s, OffsetDateTime now) {
        return ShowtimeStatus.of(s.storedStatus(), s.saleOpensAt(), s.saleClosesAt(), s.endsAt(), now);
    }

    private static DomainException showtimeNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "Suất không tồn tại");
    }

    private static ShowtimeResult toShowtime(ShowtimeRow r, OffsetDateTime now, boolean soldOut) {
        ShowtimeStatus status = ShowtimeStatus.of(r.storedStatus(), r.saleOpensAt(), r.saleClosesAt(), r.endsAt(), now);
        return new ShowtimeResult(r.id(), r.eventId(), r.hallId(), r.seatLayoutId(), r.startsAt(), r.endsAt(),
                r.saleOpensAt(), r.saleClosesAt(), status, soldOut, r.venue());
    }

    private static DomainException eventNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "Sự kiện không tồn tại");
    }
}
