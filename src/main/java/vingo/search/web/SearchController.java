package vingo.search.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import vingo.shared.error.ErrorCode;
import vingo.shared.web.GlobalExceptionHandler;

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
            Integer priceMin, Integer priceMax, String sort, String cursor, Integer limit) {
        EventSearchCriteria criteria = new EventSearchCriteria(
                q,
                category == null ? null : category.getValue(),
                city == null ? null : city.getValue(),
                from,
                to,
                priceMin == null ? null : priceMin.longValue(),
                priceMax == null ? null : priceMax.longValue(),
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

    /**
     * Interface sinh từ contract mang {@code @Validated} nên ràng buộc như {@code limit} 1..100 ném
     * {@link ConstraintViolationException} trước khi vào controller; {@code GlobalExceptionHandler} chưa xử lý loại này
     * (sẽ ra 500). Bắt tại đây để trả 400 {@code VALIDATION_ERROR} kèm {@code errors} theo field.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = GlobalExceptionHandler.problem(ErrorCode.VALIDATION_ERROR, "Có tham số không hợp lệ");
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", fieldName(v), "message", v.getMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    /** {@code searchEvents.priceMin} → {@code price_min}. */
    private static String fieldName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        String name = path.substring(path.lastIndexOf('.') + 1);
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
