package vingo.search;

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
}
