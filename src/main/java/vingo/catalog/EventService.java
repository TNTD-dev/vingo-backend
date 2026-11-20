package vingo.catalog;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import vingo.catalog.internal.EventRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private static final Set<String> CATEGORIES = Set.of("FOOTBALL", "CONCERT");

    private final EventRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EventService(EventRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    public record CreateEvent(
            String title,
            String description,
            String category,
            String homeTeam,
            String awayTeam,
            String league,
            String round,
            URI posterMobileUrl,
            URI posterDesktopUrl,
            Integer maxTicketsPerOrder
    ) {}

    public record CreatedEvent(UUID id, CreateEvent data) {}

    public record UpdateEvent(
            String title,
            String description,
            String category,
            String homeTeam,
            String awayTeam,
            String league,
            String round,
            URI posterMobileUrl,
            URI posterDesktopUrl,
            Integer maxTicketsPerOrder
    ) {}

    @Transactional
    public CreatedEvent create(CreateEvent input) {
        return create(input, null);
    }

    @Transactional
    public CreatedEvent create(CreateEvent input, UUID layoutId) {
        validateInput(input, true);
        int maxTickets = input.maxTicketsPerOrder() == null ? 10 : input.maxTicketsPerOrder();
        boolean football = "FOOTBALL".equals(input.category());

        var normalized = new CreateEvent(
                input.title().strip(),
                input.description(),
                input.category(),
                football ? trimToNull(input.homeTeam()) : null,
                football ? trimToNull(input.awayTeam()) : null,
                football ? trimToNull(input.league()) : null,
                football ? trimToNull(input.round()) : null,
                input.posterMobileUrl(),
                input.posterDesktopUrl(),
                maxTickets
        );

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        UUID id = repository.insertDraft(normalized, now);
        if (layoutId != null) {
            validateSelection(layoutId, normalized.category());
            repository.selectLayout(id, layoutId);
        }
        events.publishEvent(new CatalogChanged(id, Set.of()));

        try (var ignored = MDC.putCloseable("event_id", id.toString())) {
            log.info("Đã tạo sự kiện DRAFT");
        }
        return new CreatedEvent(id, normalized);
    }

    private static void validateInput(CreateEvent input, boolean required) {
        if ((required && input.title() == null)
                || (input.title() != null && input.title().isBlank())) {
            throw invalid("Tên sự kiện không được để trống.");
        }

        if ((required && input.category() == null)
                || (input.category() != null && !CATEGORIES.contains(input.category()))) {
            throw invalid("Loại sự kiện chỉ hỗ trợ FOOTBALL hoặc CONCERT.");
        }

        validatePoster(input.posterMobileUrl(), "poster_mobile_url");
        validatePoster(input.posterDesktopUrl(), "poster_desktop_url");

        if (input.maxTicketsPerOrder() != null
                && (input.maxTicketsPerOrder() < 1 || input.maxTicketsPerOrder() > 20)) {
            throw invalid("max_tickets_per_order phải từ 1 đến 20.");
        }
    }

    @Transactional
    public EventDetails update(UUID eventId, UpdateEvent patch) {
        return update(eventId, patch, null);
    }

    @Transactional
    public EventDetails update(UUID eventId, UpdateEvent patch, UUID layoutId) {
        validateInput(new CreateEvent(
                patch.title(), patch.description(), patch.category(),
                patch.homeTeam(), patch.awayTeam(), patch.league(), patch.round(),
                patch.posterMobileUrl(), patch.posterDesktopUrl(),
                patch.maxTicketsPerOrder()
        ), false);

        var current = repository.lockById(eventId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện."));
        if (!Set.of("DRAFT", "PUBLISHED").contains(current.status())) {
            throw new DomainException(ErrorCode.EVENT_LOCKED, "Không thể sửa sự kiện đã hủy.");
        }

        if ("FOOTBALL".equals(patch.category()) && repository.hasMultipleActiveShowtimes(eventId)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ có thể đổi sang Bóng đá khi sự kiện có tối đa một suất chưa hủy.");
        }

        var currentSelection = repository.findSelection(eventId).orElse(null);
        String category = patch.category() == null ? current.data().category() : patch.category();
        boolean categoryChanged = !category.equals(current.data().category());
        boolean selectionChanged = layoutId != null && (currentSelection == null || !layoutId.equals(currentSelection.layoutId()));

        if ((selectionChanged || (currentSelection != null && categoryChanged)) && !"DRAFT".equals(current.status())) {
            throw new DomainException(ErrorCode.EVENT_LOCKED, "Không đổi loại hoặc sơ đồ sau công bố.");
        }

        UUID targetLayout = layoutId != null ? layoutId : (currentSelection == null ? null : currentSelection.layoutId());
        if (targetLayout != null && (categoryChanged || selectionChanged)) {
            validateSelection(targetLayout, category);
            if (repository.hasConflictingShowtimes(eventId, targetLayout)) {
                throw invalid("Hủy các suất dùng sơ đồ cũ trước khi đổi sơ đồ của sự kiện.");
            }
        }
        if (selectionChanged) {
            repository.selectLayout(eventId, layoutId);
        }

        boolean clearFootballFields = !"FOOTBALL".equals(category);
        var normalized = new UpdateEvent(
                patch.title() == null ? null : patch.title().strip(),
                patch.description(),
                patch.category(),
                trimToNull(patch.homeTeam()),
                trimToNull(patch.awayTeam()),
                trimToNull(patch.league()),
                trimToNull(patch.round()),
                patch.posterMobileUrl(),
                patch.posterDesktopUrl(),
                patch.maxTicketsPerOrder()
        );
        boolean changed = repository.updateFields(eventId, normalized, clearFootballFields,
                clock.instant().atOffset(ZoneOffset.UTC));
        if (changed || selectionChanged) {
            events.publishEvent(new CatalogChanged(eventId, repository.findShowtimeIds(eventId)));
            try (var ignored = MDC.putCloseable("event_id", eventId.toString())) {
                log.info("Đã cập nhật thông tin sự kiện");
            }
        }
        return getDetails(eventId);
    }

    private void validateSelection(UUID layoutId, String category) {
        if (!repository.isValidLayout(layoutId, category)) {
            throw new DomainException(ErrorCode.INVALID_SEAT_LAYOUT,
                    "Chọn sơ đồ ghế hợp lệ thuộc sân đang hoạt động và đúng loại sự kiện.");
        }
    }

    private static void validatePoster(URI url, String field) {
        if (url == null) {
            return;
        }
        boolean supportedScheme = "http".equalsIgnoreCase(url.getScheme())
                || "https".equalsIgnoreCase(url.getScheme());
        if (!supportedScheme || url.getHost() == null || url.getHost().isBlank()) {
            throw invalid(field + " phải là URL tuyệt đối HTTP/HTTPS có host.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventDetails getDetails(UUID eventId) {
        var header = repository.findById(eventId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện."));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);

        return new EventDetails(
                header,
                java.util.List.copyOf(repository.findPriceTiers(eventId, now)),
                java.util.List.copyOf(repository.findShowtimes(eventId)),
                repository.findSelection(eventId).orElse(null),
                repository.findZonePrices(eventId)
        );
    }

    @Transactional(readOnly = true)
    public EventList list(String q, String status, String category, String cursor, Integer limit) {
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > 100) {
            throw invalid("limit phải nằm trong khoảng 1 đến 100.");
        }
        if (status != null && !Set.of("DRAFT", "PUBLISHED", "CANCELLED").contains(status)) {
            throw invalid("Trạng thái sự kiện không hợp lệ.");
        }
        if (category != null && !CATEGORIES.contains(category)) {
            throw invalid("Loại sự kiện không hợp lệ.");
        }

        String query = q == null || q.isBlank() ? null : q.strip();
        EventList.Position after = cursor == null ? null : decodeEventCursor(cursor);
        var rows = repository.findPage(query, status, category, after, pageSize + 1);

        if (rows.size() <= pageSize) {
            return new EventList(rows, null);
        }
        var page = java.util.List.copyOf(rows.subList(0, pageSize));
        return new EventList(page, encodeEventCursor(page.getLast()));
    }

    private static String encodeEventCursor(EventList.Item item) {
        String raw = item.createdAt() + "|" + item.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static EventList.Position decodeEventCursor(String cursor) {
        if (cursor.isBlank() || cursor.length() > 500) {
            throw invalid("Cursor sự kiện không hợp lệ.");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2) {
                throw invalid("Cursor sự kiện không hợp lệ.");
            }
            return new EventList.Position(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw invalid("Cursor sự kiện không hợp lệ.");
        }
    }
}
