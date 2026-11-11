package vingo.search;

import java.util.List;
import java.util.UUID;

/**
 * API công khai của module search. Toàn bộ là luồng đọc.
 */
public interface SearchService {

    /**
     * Tìm sự kiện PUBLISHED còn ít nhất một suất SCHEDULED chưa diễn ra.
     *
     * @throws InvalidSearchRequestException khi tham số sai
     */
    Page<EventSummaryResult> searchEvents(EventSearchCriteria criteria);

    /**
     * Chi tiết sự kiện: mô tả, địa điểm, bảng giá và các suất.
     *
     * @throws vingo.shared.error.DomainException NOT_FOUND khi sự kiện không tồn tại hoặc chưa PUBLISHED
     */
    EventDetailResult getEvent(UUID eventId);

    /**
     * Các suất của sự kiện theo {@code starts_at}, kèm trạng thái tính theo giờ hiện tại. Không trả suất DRAFT;
     * suất CANCELLED vẫn trả.
     *
     * @throws vingo.shared.error.DomainException NOT_FOUND khi sự kiện không tồn tại hoặc chưa PUBLISHED
     */
    List<ShowtimeResult> listEventShowtimes(UUID eventId);
}
