package vingo.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.catalog.CatalogChanged;

@Service
public class SeatLayoutImportService {
    private final SeatLayoutSvgParser parser;
    private final SeatLayoutRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    SeatLayoutImportService(SeatLayoutSvgParser parser, SeatLayoutRepository repository, Clock clock,
            ApplicationEventPublisher events) {
        this.parser = parser;
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public ImportedLayout importSvg(UUID hallId, byte[] input) {
        var layout = parser.parse(input);
        if (!repository.lockActiveVenueForHall(hallId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy hall thuộc sân đang hoạt động.");
        }

        var draft = repository.insertDraft(hallId);
        repository.insertSvg(draft.id(), layout);
        int seatCount = layout.zones().stream().mapToInt(zone -> zone.seats().size()).sum();
        UUID seatTypeId = seatCount > 0 ? repository.standardSeatType() : null;
        for (var zone : layout.zones()) {
            UUID zoneId = repository.insertZone(draft.id(), zone);
            if (!zone.seats().isEmpty()) {
                repository.insertSeats(zoneId, seatTypeId, zone.seats());
            }
        }
        return new ImportedLayout(draft.id(), hallId, draft.version(), layout.schemaVersion(),
                layout.zones().size(), seatCount);
    }

    @Transactional(readOnly = true)
    public String exportZones(UUID layoutId) {
        requireLayout(layoutId);
        return csv("zone_code,label,stand_code,level,kind,capacity,svg_id", repository.zoneRows(layoutId), 3);
    }

    @Transactional(readOnly = true)
    public String exportSeats(UUID layoutId) {
        requireLayout(layoutId);
        return csv("zone_code,row_label,seat_number,x,y", repository.seatRows(layoutId), 2);
    }

    @Transactional
    public SeatLayoutRepository.LayoutHeaderRow publish(UUID layoutId) {
        if (repository.lockPublishableLayout(layoutId).isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên bản sơ đồ sân.");
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        repository.markPublished(layoutId, now);
        return repository.findLayoutHeader(layoutId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên bản sơ đồ sân."));
    }

    private void requireLayout(UUID layoutId) {
        if (!repository.layoutExists(layoutId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên bản sơ đồ sân.");
        }
    }

    private static String csv(String header, List<String[]> rows, int protectedColumns) {
        var csv = new StringBuilder(header).append("\r\n");
        for (String[] row : rows) {
            for (int index = 0; index < row.length; index++) {
                if (index > 0) {
                    csv.append(',');
                }
                appendCell(csv, row[index], index < protectedColumns);
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private static void appendCell(StringBuilder csv, String value, boolean textColumn) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String trimmed = value.stripLeading();
        if (textColumn && (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0
                || value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n')) {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        } else {
            csv.append(value);
        }
    }

    public record ImportedLayout(UUID id, UUID hallId, long version, int schemaVersion, int zoneCount,
            int seatCount) {}

    @Transactional(readOnly = true)
    public LayoutOverview getOverview(UUID layoutId) {
        var header = repository.findLayoutHeader(layoutId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên bản sơ đồ sân."));
        return new LayoutOverview(header, repository.findZones(layoutId));
    }

    @Transactional(readOnly = true)
    public String getSvg(UUID layoutId) {
        return repository.findSvgContent(layoutId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy bản vẽ SVG của sơ đồ sân."));
    }

    @Transactional(readOnly = true)
    public ZoneDetail getZoneDetail(UUID layoutId, UUID zoneId) {
        var zone = repository.findZone(layoutId, zoneId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy khu vực trong sơ đồ sân."));
        if (zone.kind() == SeatLayoutSvgParser.ZoneKind.STANDING) {
            return new ZoneDetail(zone, null);
        }
        var seatsByRow = new java.util.LinkedHashMap<String, List<SeatLayoutRepository.SeatPointRow>>();
        for (var seat : repository.findZoneSeats(zoneId)) {
            seatsByRow.computeIfAbsent(seat.rowLabel(), ignored -> new java.util.ArrayList<>()).add(seat);
        }
    
    List<ZoneRow> rows = seatsByRow.entrySet().stream()
                .map(entry -> new ZoneRow(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new ZoneDetail(zone, rows);
    }
    public record LayoutOverview(
            SeatLayoutRepository.LayoutHeaderRow header,
            List<SeatLayoutRepository.ZoneSummaryRow> zones
    ) {}

    public record ZoneDetail(
            SeatLayoutRepository.ZoneSummaryRow zone,
            List<ZoneRow> rows
    ) {}

    public record ZoneRow(
            String rowLabel,
            List<SeatLayoutRepository.SeatPointRow> seats
    ) {}

    public record LayoutPage(
        List<SeatLayoutRepository.LayoutHeaderRow> data, 
        String nextCursor
    ) {}

    @Transactional(readOnly = true)
    public LayoutPage listByHall(UUID hallId, String cursor, Integer limit) {
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > 100) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "limit phải nằm trong khoảng 1 đến 100.");
        }
        if (!repository.activeHallExists(hallId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy hall thuộc sân đang hoạt động.");
        }
        var after = cursor == null ? null : decodeCursor(cursor);
        var rows = repository.findByHall(hallId, after, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new LayoutPage(rows, null);
        }
        var page = List.copyOf(rows.subList(0, pageSize));
        var last = page.getLast();
        String nextCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.version() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        return new LayoutPage(page, nextCursor);
    }
    private static SeatLayoutRepository.LayoutPosition decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cursor sơ đồ không hợp lệ.");
            }
            return new SeatLayoutRepository.LayoutPosition(Long.parseLong(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cursor sơ đồ không hợp lệ.");
        }
    }

    @Transactional
    public SeatLayoutRepository.ShowtimeRow createShowtime(
            UUID eventId,
            SeatLayoutRepository.ShowtimeData input
    ) {
        String eventStatus = repository.findEventStatus(eventId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện."));
        validateShowtimeData(input);
        requirePublishedLayoutForHall(input.hallId(), input.seatLayoutId());

        String showtimeStatus = "PUBLISHED".equals(eventStatus) ? "SCHEDULED" : "DRAFT";
        var created = repository.insertShowtime(eventId, showtimeStatus, input);
        repository.initShowtimeInventory(created.id(), input.seatLayoutId());
        return created;
    }

    @Transactional
    public SeatLayoutRepository.ShowtimeRow updateShowtime(
            UUID showtimeId,
            SeatLayoutRepository.ShowtimeData patch
    ) {
        var current = repository.lockShowtime(showtimeId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy suất diễn."));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if ("CANCELLED".equals(current.status())
                || !now.isBefore(current.data().saleOpensAt())) {
            throw new DomainException(ErrorCode.SHOWTIME_LOCKED, "Suất diễn đã mở bán, không thể chỉnh sửa.");
        }

        var merged = new SeatLayoutRepository.ShowtimeData(
                keepIfAbsent(patch.hallId(), current.data().hallId()),
                keepIfAbsent(patch.seatLayoutId(), current.data().seatLayoutId()),
                keepIfAbsent(patch.startsAt(), current.data().startsAt()),
                keepIfAbsent(patch.endsAt(), current.data().endsAt()),
                keepIfAbsent(patch.saleOpensAt(), current.data().saleOpensAt()),
                keepIfAbsent(patch.saleClosesAt(), current.data().saleClosesAt())
        );
        validateShowtimeData(merged);
        requirePublishedLayoutForHall(merged.hallId(), merged.seatLayoutId());

        var updated = repository.updateShowtime(showtimeId, merged);
        if (!merged.seatLayoutId().equals(current.data().seatLayoutId())) {
            repository.replaceShowtimeInventory(showtimeId, merged.seatLayoutId());
            events.publishEvent(new CatalogChanged(current.eventId(), Set.of(showtimeId)));
        }
        return updated;
    }

    private void requirePublishedLayoutForHall(UUID hallId, UUID layoutId) {
        if (!repository.lockActiveVenueForHall(hallId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy hall thuộc sân đang hoạt động.");
        }
        var layout = repository.findLayoutForAttach(layoutId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên bản sơ đồ sân."));
        if (!layout.hallId().equals(hallId)) {
            throw new DomainException(ErrorCode.INVALID_SEAT_LAYOUT, "Sơ đồ sân không thuộc hall đã chọn.");
        }
        if (!"PUBLISHED".equals(layout.status())) {
            throw new DomainException(
                    ErrorCode.INVALID_SEAT_LAYOUT,
                    "Chỉ được gắn phiên bản sơ đồ đã PUBLISHED vào suất diễn."
            );
        }
    }

    private static void validateShowtimeData(SeatLayoutRepository.ShowtimeData data) {
        if (data.hallId() == null || data.seatLayoutId() == null
                || data.startsAt() == null || data.endsAt() == null
                || data.saleOpensAt() == null || data.saleClosesAt() == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cần cung cấp đầy đủ thông tin hall, sơ đồ và thời gian suất diễn.");
        }
        if (!data.endsAt().isAfter(data.startsAt())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Thời gian kết thúc phải sau thời gian bắt đầu.");
        }
        if (!data.saleClosesAt().isAfter(data.saleOpensAt())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Thời điểm đóng bán phải sau thời điểm mở bán.");
        }
        if (data.saleClosesAt().isAfter(data.endsAt())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Thời điểm đóng bán không được sau khi suất diễn kết thúc.");
        }
    }

    private static <T> T keepIfAbsent(T incoming, T current) {
        return incoming == null ? current : incoming;
    }
}
