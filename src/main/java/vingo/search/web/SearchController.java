package vingo.search.web;

import java.time.OffsetDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.SearchApi;
import vingo.api.model.CityCode;
import vingo.api.model.EventCategory;
import vingo.api.model.SearchEvents200Response;
import vingo.search.EventSearchCriteria;
import vingo.search.EventSearchCriteria.After;
import vingo.search.EventSearchCriteria.Sort;
import vingo.search.EventSummaryResult;
import vingo.search.InvalidSearchRequestException;
import vingo.search.Page;
import vingo.search.SearchService;

/** Endpoint tìm kiếm công khai. Các operation khác của tag Search chưa hiện thực nên giữ mặc định 501. */
@RestController
class SearchController implements SearchApi {

    private final SearchService searchService;
    private final SearchApiMapper mapper;
    private final CursorCodec cursors;

    SearchController(SearchService searchService, SearchApiMapper mapper, CursorCodec cursors) {
        this.searchService = searchService;
        this.mapper = mapper;
        this.cursors = cursors;
    }

    @Override
    public ResponseEntity<SearchEvents200Response> searchEvents(
            String q, EventCategory category, CityCode city, OffsetDateTime from, OffsetDateTime to,
            Long priceMin, Long priceMax, String sort, String cursor, Integer limit) {
        EventSearchCriteria criteria = new EventSearchCriteria(
                q,
                category == null ? null : category.getValue(),
                city == null ? null : city.getValue(),
                from,
                to,
                priceMin,
                priceMax,
                Sort.parse(sort),
                limit == null ? 20 : limit,
                null);

        if (cursor != null && !cursor.isBlank()) {
            CursorCodec.Payload payload = cursors.decode(cursor);
            if (!cursors.matches(payload, criteria.sort().value(), criteria.filterSignature())) {
                throw new InvalidSearchRequestException("cursor", "cursor không khớp bộ lọc hoặc sort hiện tại");
            }
            criteria = criteria.withAfter(new After(payload.k(), payload.i()));
        }

        Page<EventSummaryResult> page = searchService.searchEvents(criteria);

        String nextCursor = null;
        if (page.hasMore()) {
            EventSummaryResult last = page.items().getLast();
            nextCursor = cursors.encode(criteria.sort().value(), last.sortKey(), last.id(), criteria.filterSignature());
        }
        return ResponseEntity.ok(new SearchEvents200Response()
                .data(page.items().stream().map(mapper::toEventSummary).toList())
                .nextCursor(nextCursor));
    }
}
