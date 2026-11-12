package vingo.catalog;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import vingo.catalog.internal.VenueRepository;
import vingo.catalog.internal.VenueRepository.HallRow;
import vingo.catalog.internal.VenueRepository.VenueData;
import vingo.catalog.internal.VenueRepository.VenuePosition;
import vingo.catalog.internal.VenueRepository.VenueRow;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;


@Service
public class VenueService {

    private final VenueRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public VenueService(VenueRepository repository, Clock clock, ApplicationEventPublisher events) {
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    public record VenuePage(List<VenueRow> data, String nextCursor) {}

    @Transactional
    public VenueRow create(VenueData input) {
        validateText(input.name(), "Tên sân");
        validateText(input.address(), "Địa chỉ");
        validateCoordinates(input);
        return repository.insert(input);
    }

    @Transactional
    public HallRow createHall(UUID venueId, String name) {
        validateText(name, "Tên hall");
        String normalizedName = name.strip();

        lockActiveVenue(venueId);

        return repository.insertHall(venueId, normalizedName)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.HALL_NAME_TAKEN,
                        "Sân này đã có hall tên " + normalizedName + "."
                ));
    }
    @Transactional(readOnly = true)
    public Map<UUID, List<HallRow>> listHallsForVenues(
            List<UUID> venueIds
    ) {
        return repository.findHallsForVenues(venueIds).stream()
                .collect(Collectors.groupingBy(HallRow::venueId));
    }

    @Transactional(readOnly = true)
    public VenuePage list(String q, String city, String cursor, Integer limit) {
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > 100) {
            throw invalid("limit phải nằm trong khoảng 1 đến 100.");
        }

        String query = q == null || q.isBlank() ? null : q.strip();
        VenuePosition after = cursor == null ? null : decodeCursor(cursor);
        List<VenueRow> rows = repository.findActive(query, city, after, pageSize + 1);

        if (rows.size() <= pageSize) {
            return new VenuePage(rows, null);
        }

        List<VenueRow> page = List.copyOf(rows.subList(0, pageSize));
        return new VenuePage(page, encodeCursor(page.getLast()));
    }

    @Transactional
    public VenueRow update(UUID venueId, VenueData patch) {
        VenueData current = lockActiveVenue(venueId).data();

        if (patch.name() != null) {
            validateText(patch.name(), "Tên sân");
        }
        if (patch.address() != null) {
            validateText(patch.address(), "Địa chỉ");
        }

        var merged = new VenueData(
                keepIfAbsent(patch.name(), current.name()),
                keepIfAbsent(patch.type(), current.type()),
                keepIfAbsent(patch.address(), current.address()),
                keepIfAbsent(patch.city(), current.city()),
                keepIfAbsent(patch.district(), current.district()),
                keepIfAbsent(patch.lat(), current.lat()),
                keepIfAbsent(patch.lng(), current.lng()),
                keepIfAbsent(patch.infoJson(), current.infoJson())
        );

        validateCoordinates(merged);
        var updated = repository.update(venueId, merged, now())
                .orElseThrow(VenueService::notFound);
        events.publishEvent(new VenueChanged(venueId));
        return updated;
    }

    @Transactional
    public void delete(UUID venueId) {
        lockActiveVenue(venueId);
        OffsetDateTime timestamp = now();

        if (repository.hasUnfinishedScheduledShowtime(venueId, timestamp)) {
            throw new DomainException(
                    ErrorCode.VENUE_IN_USE,
                    "Sân còn suất diễn chưa kết thúc."
            );
        }

        if (!repository.markDeleted(venueId, timestamp)) {
            throw notFound();
        }
        events.publishEvent(new VenueChanged(venueId));
    }

    private VenueRow lockActiveVenue(UUID venueId) {
        return repository.findActiveByIdForUpdate(venueId)
                .orElseThrow(VenueService::notFound);
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static <T> T keepIfAbsent(T incoming, T current) {
        return incoming == null ? current : incoming;
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " không được để trống.");
        }
    }

    private static void validateCoordinates(VenueData data) {
        if ((data.lat() == null) != (data.lng() == null)) {
            throw invalid("Cần cung cấp đủ cả lat và lng.");
        }

        if (data.lat() != null && (
                data.lat().abs().compareTo(java.math.BigDecimal.valueOf(90)) > 0
                || data.lng().abs().compareTo(java.math.BigDecimal.valueOf(180)) > 0
        )) {
            throw invalid("Tọa độ sân nằm ngoài phạm vi hợp lệ.");
        }
    }

    private static String encodeCursor(VenueRow row) {
        String value = row.createdAt() + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static VenuePosition decodeCursor(String cursor) {
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8
            );
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw invalid("Cursor sân không hợp lệ.");
            }
            return new VenuePosition(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            throw invalid("Cursor sân không hợp lệ.");
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sân đang hoạt động.");
    }
}
