package vingo.search.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.search.EventSearchCriteria;
import vingo.search.EventSummaryResult;
import vingo.search.Page;
import vingo.search.SearchService;
import vingo.search.internal.SearchQueryNormalizer.NormalizedQuery;

/** Hiện thực {@link SearchService} bằng SQL chỉ đọc. Giờ lấy từ {@link Clock}, truyền vào SQL. */
@Service
@Transactional(readOnly = true)
class PgSearchService implements SearchService {

    private final EventSearchRepository events;
    private final SearchQueryNormalizer normalizer;
    private final Clock clock;

    PgSearchService(EventSearchRepository events, SearchQueryNormalizer normalizer, Clock clock) {
        this.events = events;
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
        // Lấy dư một dòng để biết còn trang sau.
        List<EventSummaryResult> rows = events.search(criteria, query, OffsetDateTime.now(clock), criteria.limit() + 1);
        boolean hasMore = rows.size() > criteria.limit();
        return new Page<>(hasMore ? rows.subList(0, criteria.limit()) : rows, hasMore);
    }
}
