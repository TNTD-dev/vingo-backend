package vingo.search.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import vingo.search.AvailabilityResult;
import vingo.search.ZoneAvailabilityResult;
import vingo.search.internal.EventDetailRepository.ShowtimeRow;
import vingo.search.internal.SeatmapRepository.SeatmapLayout;
import vingo.search.internal.SeatmapRepository.ShowtimeRef;
import vingo.search.internal.SeatmapRepository.ZoneRow;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/**
 * Các lần đọc có cache của search. Tách thành bean riêng vì {@code @Cacheable} chỉ chạy khi gọi qua proxy, không
 * chạy khi {@code PgSearchService} tự gọi method của nó.
 *
 * <p>Chỉ cache dữ liệu thô. Trạng thái suất ({@code ShowtimeStatus}), {@code sold_out} và trạng thái ghế động do
 * {@code PgSearchService} tính lại mỗi lần đọc. Kết quả rỗng (không tìm thấy) không được cache.
 *
 * <p>Khoá của event, suất và layout gồm thế hệ ({@link CacheGenerations}), đọc trước khi truy vấn DB.
 */
@Component
class SearchReads {

    private final EventDetailRepository details;
    private final SeatmapRepository seatmaps;
    private final Clock clock;

    SearchReads(EventDetailRepository details, SeatmapRepository seatmaps, Clock clock) {
        this.details = details;
        this.seatmaps = seatmaps;
        this.clock = clock;
    }

    /** Sự kiện PUBLISHED kèm bảng giá và các suất thô; rỗng nếu không tồn tại hoặc chưa PUBLISHED. */
    @Cacheable(cacheNames = SearchCacheConfig.EVENT, key = "@cacheGenerations.eventKey(#eventId)", unless = "#result == null")
    Optional<EventCore> eventCore(UUID eventId) {
        return details.findPublished(eventId).map(event ->
                new EventCore(event, details.findPriceTiers(eventId), details.findShowtimes(eventId)));
    }

    /** Các suất thô của sự kiện PUBLISHED; rỗng nếu sự kiện không tồn tại hoặc chưa PUBLISHED. */
    @Cacheable(cacheNames = SearchCacheConfig.SHOWTIMES, key = "@cacheGenerations.eventKey(#eventId)", unless = "#result == null")
    Optional<List<ShowtimeRow>> showtimes(UUID eventId) {
        return details.findPublished(eventId).map(event -> details.findShowtimes(eventId));
    }

    /** Layout tĩnh của suất. Người gọi đã kiểm suất hiển thị được. */
    @Cacheable(cacheNames = SearchCacheConfig.SEATMAP_LAYOUT, key = "@cacheGenerations.showtimeKey(#showtime.id())")
    SeatmapLayout layout(ShowtimeRef showtime) {
        return seatmaps.findLayout(showtime);
    }

    /**
     * Số chỗ còn theo khu và {@code sold_out}. Cache 3 giây; {@code sync = true} để nhiều request cùng miss chỉ
     * truy vấn DB một lần. Trạng thái suất trong kết quả có thể trễ tối đa 3 giây.
     *
     * @throws DomainException NOT_FOUND khi suất không hiển thị được (không bị cache)
     */
    @Cacheable(cacheNames = SearchCacheConfig.AVAILABILITY, key = "#showtimeId.toString()", sync = true)
    AvailabilityResult availability(UUID showtimeId) {
        ShowtimeRef showtime = seatmaps.findVisibleShowtime(showtimeId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Suất không tồn tại"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ZoneRow> zones = seatmaps.countZones(showtime, now);
        PgSearchService.warnMissing(showtimeId, zones.stream().mapToInt(ZoneRow::missingSeats).sum(),
                (int) zones.stream().filter(ZoneRow::stockMissing).count());
        return new AvailabilityResult(
                showtimeId, PgSearchService.statusOf(showtime, now), zones.stream().allMatch(z -> z.seatsLeft() == 0),
                zones.stream().map(z -> new ZoneAvailabilityResult(z.id(), z.name(), z.tier(), z.seatsLeft())).toList());
    }
}
