package vingo.search.web;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.SearchApi;
import vingo.api.model.CityCode;
import vingo.api.model.EventCategory;
import vingo.api.model.EventDetail;
import vingo.api.model.GetAvailability200Response;
import vingo.api.model.ListEventShowtimes200Response;
import vingo.api.model.SeatMap;
import vingo.api.model.SearchEvents200Response;
import vingo.search.EventSearchCriteria;
import vingo.search.EventSearchCriteria.After;
import vingo.search.EventSearchCriteria.Sort;
import vingo.search.EventSummaryResult;
import vingo.search.InvalidSearchRequestException;
import vingo.search.Page;
import vingo.search.SearchService;

/** Endpoint tìm kiếm và xem chi tiết sự kiện, sơ đồ ghế, số chỗ còn công khai. Các operation khác của tag Search chưa hiện thực nên giữ mặc định 501. */
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
            criteria = criteria.withAfter(new After(payload.k(), payload.i(), payload.matchMode()));
        }

        Page<EventSummaryResult> page = searchService.searchEvents(criteria);

        String nextCursor = null;
        if (page.hasMore()) {
            EventSummaryResult last = page.items().getLast();
            nextCursor = cursors.encode(
                    criteria.sort().value(), last.sortKey(), last.id(), criteria.filterSignature(), page.matchMode());
        }
        return ResponseEntity.ok(new SearchEvents200Response()
                .data(page.items().stream().map(mapper::toEventSummary).toList())
                .nextCursor(nextCursor));
    }

    @Override
    public ResponseEntity<EventDetail> getEvent(UUID eventId) {
        return ResponseEntity.ok(mapper.toEventDetail(searchService.getEvent(eventId)));
    }

    @Override
    public ResponseEntity<ListEventShowtimes200Response> listEventShowtimes(UUID eventId) {
        return ResponseEntity.ok(new ListEventShowtimes200Response()
                .data(searchService.listEventShowtimes(eventId).stream().map(mapper::toShowtime).toList()));
    }

    @Override
    public ResponseEntity<SeatMap> getSeatmap(UUID showtimeId, UUID zoneId) {
        return ResponseEntity.ok(mapper.toSeatMap(searchService.getSeatmap(showtimeId, zoneId)));
    }

    @Override
    public ResponseEntity<GetAvailability200Response> getAvailability(UUID showtimeId) {
        return ResponseEntity.ok(mapper.toAvailability(searchService.getAvailability(showtimeId)));
    }
}
