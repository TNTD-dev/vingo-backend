package vingo.catalog.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.json.JsonMapper;
import vingo.api.AdminCatalogApi;
import vingo.api.model.CityCode;
import vingo.api.model.CreateHallRequest;
import vingo.api.model.Hall;
import vingo.api.model.ListVenues200Response;
import vingo.api.model.SeatLayout;
import vingo.api.model.SeatLayoutBounds;
import vingo.api.model.SeatLayoutOverview;
import vingo.api.model.SeatLayoutPage;
import vingo.api.model.SeatLayoutZone;
import vingo.api.model.SeatLayoutZoneDetail;
import vingo.api.model.SeatLayoutZoneDetailAllOfRows;
import vingo.api.model.SeatLayoutZoneDetailAllOfSeats;
import vingo.api.model.Showtime;
import vingo.api.model.ShowtimeInput;
import vingo.api.model.ShowtimeStatus;
import vingo.api.model.Venue;
import vingo.api.model.VenueCreateInput;
import vingo.api.model.VenueInfo;
import vingo.api.model.VenueInput;
import vingo.catalog.VenueService;
import vingo.catalog.internal.SeatLayoutImportService;
import vingo.catalog.internal.SeatLayoutRepository.LayoutHeaderRow;
import vingo.catalog.internal.SeatLayoutRepository.ShowtimeData;
import vingo.catalog.internal.SeatLayoutRepository.ShowtimeRow;
import vingo.catalog.internal.SeatLayoutRepository.ZoneSummaryRow;
import vingo.catalog.internal.SeatLayoutSvgParser.Bounds;
import vingo.catalog.internal.VenueRepository.VenueData;
import vingo.catalog.internal.VenueRepository.VenueRow;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@RestController
class AdminVenueController implements AdminCatalogApi {
    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final VenueService venues;
    private final SeatLayoutImportService layouts;
    private final JsonMapper json;

    AdminVenueController(VenueService venues, SeatLayoutImportService layouts, JsonMapper json) {
        this.venues = venues;
        this.layouts = layouts;
        this.json = json;
    }

    @Override
    public ResponseEntity<Venue> createVenue(VenueCreateInput input) {
        VenueRow created = venues.create(new VenueData(
                input.getName(),
                input.getType().getValue(),
                input.getAddress(),
                input.getCity().getValue(),
                input.getDistrict(),
                input.getLat(),
                input.getLng(),
                toJson(input.getInfo())
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(created));
    }

    @Override
    public ResponseEntity<ListVenues200Response> listVenues(
            String q, String city, String cursor, Integer limit
    ) {
        VenueService.VenuePage page = venues.list(q, city, cursor, limit);
        return ResponseEntity.ok(new ListVenues200Response()
                .data(page.data().stream().map(this::toApi).toList())
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<Venue> updateVenue(UUID venueId, VenueInput input) {
        VenueRow updated = venues.update(venueId, new VenueData(
                input.getName(),
                input.getType() == null ? null : input.getType().getValue(),
                input.getAddress(),
                input.getCity() == null ? null : input.getCity().getValue(),
                input.getDistrict(),
                input.getLat(),
                input.getLng(),
                toJson(input.getInfo())
        ));

        return ResponseEntity.ok(toApi(updated));
    }

    @Override
    public ResponseEntity<Void> deleteVenue(UUID venueId) {
        venues.delete(venueId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Hall> createHall(UUID venueId, CreateHallRequest input) {
        var created = venues.createHall(venueId, input.getName());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Hall()
                        .id(created.id())
                        .venueId(created.venueId())
                        .name(created.name()));
    }

    @Override
    public ResponseEntity<SeatLayout> importSeatLayoutSvg(UUID hallId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "File SVG không được để trống.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !SVG.equalsTypeAndSubtype(MediaType.parseMediaType(contentType))) {
            throw new DomainException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "File phải có kiểu image/svg+xml.");
        }

        byte[] input;
        try {
            input = file.getBytes();
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Không đọc được file đã tải lên.");
        }
        var created = layouts.importSvg(hallId, input);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SeatLayout()
                .id(created.id())
                .hallId(created.hallId())
                .version(created.version())
                .schemaVersion(created.schemaVersion())
                .status(SeatLayout.StatusEnum.DRAFT)
                .zoneCount(created.zoneCount())
                .seatCount(created.seatCount()));
    }

    @Override
    public ResponseEntity<String> exportSeatLayoutZones(UUID layoutId) {
        return downloadCsv("zones.csv", layouts.exportZones(layoutId));
    }

    @Override
    public ResponseEntity<String> exportSeatLayoutSeats(UUID layoutId) {
        return downloadCsv("seats.csv", layouts.exportSeats(layoutId));
    }

    private static ResponseEntity<String> downloadCsv(String filename, String content) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }

    private String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
    
    private static CityCode toCityCode(String storedCity) {
        for (CityCode code : CityCode.values()) {
            if (code.getValue().equals(storedCity)) {
                return code;
            }
        }
        return null;
    }

    private Venue toApi(VenueRow row) {
        VenueData data = row.data();
        CityCode city = toCityCode(data.city());
        // Tên hiển thị theo CityCode trong contract hiện hành; danh mục còn chờ catalog chốt.
        String cityName = city == null ? data.city() : switch (city) {
            case HCM -> "TP. Hồ Chí Minh";
            case HN -> "Hà Nội";
            case DN -> "Đà Nẵng";
        };

        return new Venue()
                .id(row.id())
                .name(data.name())
                .type(Venue.TypeEnum.fromValue(data.type()))
                .address(data.address())
                .city(city)
                .cityName(cityName)
                .district(data.district())
                .lat(data.lat())
                .lng(data.lng())
                .info(json.readValue(data.infoJson(), VenueInfo.class));
    }
    
    @Override
    public ResponseEntity<SeatLayoutOverview> getSeatLayout(UUID layoutId) {
        var overview = layouts.getOverview(layoutId);
        var header = overview.header();
        return ResponseEntity.ok(new SeatLayoutOverview()
                .id(header.id())
                .hallId(header.hallId())
                .status(SeatLayoutOverview.StatusEnum.fromValue(header.status()))
                .zoneCount(header.zoneCount())
                .seatCount(header.seatCount())
                .version(header.version())
                .schemaVersion(header.schemaVersion())
                .publishedAt(header.publishedAt())
                .viewBox(toBounds(header.viewBox()))
                .zones(overview.zones().stream().map(AdminVenueController::toZone).toList()));
    }

    @Override
    public ResponseEntity<Resource> getSeatLayoutSvg(UUID layoutId) {
        byte[] content = layouts.getSvg(layoutId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(SVG)
                .body(new ByteArrayResource(content));
    }

    @Override
    public ResponseEntity<SeatLayoutZoneDetail> getSeatLayoutZone(UUID layoutId, UUID zoneId) {
        var detail = layouts.getZoneDetail(layoutId, zoneId);
        var zone = detail.zone();
        var response = new SeatLayoutZoneDetail()
                .id(zone.id())
                .code(zone.code())
                .label(zone.label())
                .standCode(zone.standCode())
                .level(zone.level())
                .kind(SeatLayoutZoneDetail.KindEnum.fromValue(zone.kind().name()))
                .capacity(zone.capacity())
                .svgId(zone.svgId())
                .contentSvgId(zone.contentSvgId())
                .pitchSideSvgId(zone.pitchSideSvgId())
                .bounds(toBounds(zone.bounds()))
                .rows(detail.rows() == null ? null : detail.rows().stream()
                        .map(row -> new SeatLayoutZoneDetailAllOfRows()
                                .rowLabel(row.rowLabel())
                                .seats(row.seats().stream()
                                        .map(seat -> new SeatLayoutZoneDetailAllOfSeats()
                                                .id(seat.id())
                                                .number(seat.number())
                                                .x(seat.x())
                                                .y(seat.y()))
                                        .toList()))
                        .toList());
        return ResponseEntity.ok(response);
    }

    private static SeatLayoutZone toZone(ZoneSummaryRow zone) {
        SeatLayoutZone response = new SeatLayoutZone();
        response.setId(zone.id());
        response.setCode(zone.code());
        response.setLabel(zone.label());
        response.setStandCode(zone.standCode());
        response.setLevel(zone.level());
        response.setKind(SeatLayoutZone.KindEnum.fromValue(zone.kind().name()));
        response.setCapacity(zone.capacity());
        response.setSvgId(zone.svgId());
        response.setContentSvgId(zone.contentSvgId());
        response.setPitchSideSvgId(zone.pitchSideSvgId());
        response.setBounds(toBounds(zone.bounds()));
        return response;
    }

    private static SeatLayoutBounds toBounds(Bounds bounds) {
        if (bounds == null) {
            return null;
        }
        return new SeatLayoutBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    @Override
    public ResponseEntity<SeatLayout> publishSeatLayout(UUID layoutId) {
        return ResponseEntity.ok(toSeatLayout(layouts.publish(layoutId)));
    }

    @Override
    public ResponseEntity<SeatLayoutPage> listSeatLayouts(UUID hallId, String cursor, Integer limit) {
        var page = layouts.listByHall(hallId, cursor, limit);
        return ResponseEntity.ok(new SeatLayoutPage()
                .data(page.data().stream().map(AdminVenueController::toSeatLayout).toList())
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<Showtime> createShowtime(UUID eventId, ShowtimeInput input) {
        var created = layouts.createShowtime(eventId, toShowtimeData(input));
        return ResponseEntity.status(HttpStatus.CREATED).body(toShowtime(created));
    }

    @Override
    public ResponseEntity<Showtime> updateShowtime(UUID showtimeId, ShowtimeInput input) {
        var updated = layouts.updateShowtime(showtimeId, toShowtimeData(input));
        return ResponseEntity.ok(toShowtime(updated));
    }

    private static SeatLayout toSeatLayout(LayoutHeaderRow header) {
        return new SeatLayout()
                .id(header.id())
                .hallId(header.hallId())
                .status(SeatLayout.StatusEnum.fromValue(header.status()))
                .zoneCount(header.zoneCount())
                .seatCount(header.seatCount())
                .version(header.version())
                .schemaVersion(header.schemaVersion())
                .publishedAt(header.publishedAt());
    }

    private static ShowtimeData toShowtimeData(ShowtimeInput input) {
        return new ShowtimeData(
                input.getHallId(),
                input.getSeatLayoutId(),
                input.getStartsAt(),
                input.getEndsAt(),
                input.getSaleOpensAt(),
                input.getSaleClosesAt()
        );
    }

    private static Showtime toShowtime(ShowtimeRow row) {
        var data = row.data();
        return new Showtime()
                .id(row.id())
                .eventId(row.eventId())
                .status(ShowtimeStatus.fromValue(row.status()))
                .hallId(data.hallId())
                .seatLayoutId(data.seatLayoutId())
                .startsAt(data.startsAt())
                .endsAt(data.endsAt())
                .saleOpensAt(data.saleOpensAt())
                .saleClosesAt(data.saleClosesAt());
    }
}
