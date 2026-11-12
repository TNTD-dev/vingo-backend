package vingo.catalog.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.json.JsonMapper;
import vingo.api.AdminCatalogApi;
import vingo.api.model.AdminEventPage;
import vingo.api.model.AdminEventSummary;
import vingo.api.model.CityCode;
import vingo.api.model.CreateHallRequest;
import vingo.api.model.EventCategory;
import vingo.api.model.EventCreateInput;
import vingo.api.model.EventDetail;
import vingo.api.model.EventPatchInput;
import vingo.api.model.EventStatus;
import vingo.api.model.Hall;
import vingo.api.model.ListVenues200Response;
import vingo.api.model.PriceTier;
import vingo.api.model.PriceTierInput;
import vingo.api.model.SeatLayout;
import vingo.api.model.SeatLayoutBounds;
import vingo.api.model.SeatLayoutNameInput;
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
import vingo.api.model.ZonePriceInput;
import vingo.catalog.EventDetails;
import vingo.catalog.EventPublishingService;
import vingo.catalog.SeatBlockingService;
import vingo.api.model.BlockSeatsRequest;
import vingo.api.model.UnblockSeatsRequest;
import vingo.catalog.EventPricingService;
import vingo.catalog.EventService;
import vingo.catalog.PriceTierChange;
import vingo.catalog.VenueService;
import vingo.catalog.ZonePriceChange;
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
    private final Clock clock;
    private final EventService eventService;
    private final EventPricingService eventPricing;
    private final SeatBlockingService seatBlocking;
    private final EventPublishingService publishing;

    AdminVenueController(
            VenueService venues,
            SeatLayoutImportService layouts,
            JsonMapper json,
            Clock clock,
            EventService eventService,
            EventPricingService eventPricing,
            SeatBlockingService seatBlocking,
            EventPublishingService publishing
    ) {
        this.venues = venues;
        this.layouts = layouts;
        this.json = json;
        this.clock = clock;
        this.eventService = eventService;
        this.eventPricing = eventPricing;
        this.seatBlocking = seatBlocking;
        this.publishing = publishing;
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
    public ResponseEntity<EventDetail> createEvent(EventCreateInput input) {
        var created = eventService.create(new EventService.CreateEvent(
                input.getTitle(),
                input.getDescription(),
                input.getCategory() == null
                ? null : input.getCategory().getValue(),
                input.getAttributes(),
                input.getPosterMobileUrl(),
                input.getPosterDesktopUrl(),
                input.getAgeLimit(),
                input.getMaxTicketsPerOrder()
        ));

        var data = created.data();

        var response = new EventDetail()
                .id(created.id())
                .status(EventStatus.DRAFT)
                .title(data.title())
                .description(data.description())
                .category(EventCategory.fromValue(data.category()))
                .attributes(data.attributes())
                .posterMobileUrl(data.posterMobileUrl())
                .posterDesktopUrl(data.posterDesktopUrl())
                .ageLimit(data.ageLimit())
                .maxTicketsPerOrder(data.maxTicketsPerOrder())
                .priceTiers(List.of())
                .showtimes(List.of());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    public ResponseEntity<ListVenues200Response> listVenues(
            String q,
            String city,
            String cursor,
            Integer limit
    ) {
        VenueService.VenuePage page
                = venues.list(q, city, cursor, limit);

        var venueIds = page.data().stream()
                .map(VenueRow::id)
                .toList();

        var hallsByVenue = venues.listHallsForVenues(venueIds);

        var data = page.data().stream()
                .map(row -> {
                    var halls = hallsByVenue
                            .getOrDefault(row.id(), List.of())
                            .stream()
                            .map(hall -> new Hall()
                                    .id(hall.id())
                                    .venueId(hall.venueId())
                                    .name(hall.name()))
                            .toList();

                    return toApi(row).halls(halls);
                })
                .toList();

        return ResponseEntity.ok(new ListVenues200Response()
                .data(data)
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
    public ResponseEntity<SeatLayout> importSeatLayoutSvg(UUID hallId, MultipartFile file, String name) {
        if (file.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "File SVG không được để trống.");
        }
        String contentType = file.getContentType();
        if (!isSvg(contentType)) {
            throw new DomainException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "File phải có kiểu image/svg+xml.");
        }

        byte[] input;
        try {
            input = file.getBytes();
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Không đọc được file đã tải lên.");
        }
        var created = layouts.importSvg(hallId, name, input);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SeatLayout()
                .id(created.id())
                .hallId(created.hallId())
                .name(created.name())
                .version(created.version())
                .schemaVersion(created.schemaVersion())
                .status(SeatLayout.StatusEnum.DRAFT)
                .zoneCount(created.zoneCount())
                .seatCount(created.seatCount()));
    }

    private static boolean isSvg(String contentType) {
        try {
            return contentType != null && SVG.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
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
            case HCM ->
                "TP. Hồ Chí Minh";
            case HN ->
                "Hà Nội";
            case DN ->
                "Đà Nẵng";
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
                .name(header.name())
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
    public ResponseEntity<SeatLayout> renameSeatLayout(UUID layoutId, SeatLayoutNameInput input) {
        return ResponseEntity.ok(toSeatLayout(layouts.rename(layoutId, input.getName())));
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

    @Override
    public ResponseEntity<EventDetail> getAdminEvent(UUID eventId) {
        return ResponseEntity.ok(toAdminEventDetail(eventService.getDetails(eventId)));
    }

    @Override
    public ResponseEntity<EventDetail> updateEvent(UUID eventId, EventPatchInput input) {
        var result = eventService.update(eventId, new EventService.UpdateEvent(
                input.getTitle(), input.getDescription(), input.getCategory(), input.getAttributes(),
                input.getPosterMobileUrl(), input.getPosterDesktopUrl(), input.getAgeLimit(),
                input.getMaxTicketsPerOrder()
        ));
        return ResponseEntity.ok(toAdminEventDetail(result));
    }

    private EventDetail toAdminEventDetail(EventDetails result) {
        var header = result.header();
        var data = header.data();

        OffsetDateTime now
                = clock.instant().atOffset(ZoneOffset.UTC);

        var response = new EventDetail()
                .id(header.id())
                .status(EventStatus.fromValue(header.status()))
                .title(data.title())
                .description(data.description())
                .category(EventCategory.fromValue(data.category()))
                .attributes(data.attributes())
                .posterMobileUrl(data.posterMobileUrl())
                .posterDesktopUrl(data.posterDesktopUrl())
                .ageLimit(data.ageLimit())
                .maxTicketsPerOrder(data.maxTicketsPerOrder())
                .priceTiers(result.priceTiers().stream()
                        .map(tier -> new PriceTier()
                                .id(tier.id())
                                .name(tier.name())
                                .price(tier.price())
                                .color(tier.color()))
                        .toList())
                .showtimes(result.showtimes().stream()
                        .map(session -> toAdminShowtime(session, now))
                        .toList());

        return response;
    }

    private static SeatLayout toSeatLayout(LayoutHeaderRow header) {
        return new SeatLayout()
                .id(header.id())
                .hallId(header.hallId())
                .name(header.name())
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

    private Showtime toShowtime(ShowtimeRow row) {
        var data = row.data();
        return new Showtime()
                .id(row.id())
                .eventId(row.eventId())
                .status(ShowtimeStatus.fromValue(vingo.catalog.ShowtimeStatus.of(row.status(),
                        data.saleOpensAt(), data.saleClosesAt(), data.endsAt(),
                        OffsetDateTime.now(clock)).name()))
                .hallId(data.hallId())
                .seatLayoutId(data.seatLayoutId())
                .startsAt(data.startsAt())
                .endsAt(data.endsAt())
                .saleOpensAt(data.saleOpensAt())
                .saleClosesAt(data.saleClosesAt());
    }

    private Showtime toAdminShowtime(
            EventDetails.Session session,
            OffsetDateTime now
    ) {
        var status = vingo.catalog.ShowtimeStatus.of(
                session.storedStatus(),
                session.saleOpensAt(),
                session.saleClosesAt(),
                session.endsAt(),
                now
        );

        return new Showtime()
                .id(session.id())
                .eventId(session.eventId())
                .hallId(session.hallId())
                .seatLayoutId(session.seatLayoutId())
                .status(ShowtimeStatus.fromValue(status.name()))
                .startsAt(session.startsAt())
                .endsAt(session.endsAt())
                .saleOpensAt(session.saleOpensAt())
                .saleClosesAt(session.saleClosesAt())
                .venue(toAdminEventVenue(session.venue()));
    }

    private Venue toAdminEventVenue(EventDetails.VenueInfo source) {
        CityCode city = Arrays.stream(CityCode.values())
                .filter(code -> code.getValue().equals(source.city()))
                .findFirst()
                .orElse(null);

        String cityName = city == null
                ? source.city()
                : switch (city) {
            case HCM ->
                "TP. Hồ Chí Minh";
            case HN ->
                "Hà Nội";
            case DN ->
                "Đà Nẵng";
        };

        return new Venue()
                .id(source.id())
                .name(source.name())
                .type(Venue.TypeEnum.fromValue(source.type()))
                .address(source.address())
                .city(city)
                .cityName(cityName);
    }

    @Override
    public ResponseEntity<AdminEventPage> listAdminEvents(
            String q,
            EventStatus status,
            EventCategory category,
            String cursor,
            Integer limit
    ) {
        var page = eventService.list(
                q,
                status == null ? null : status.getValue(),
                category == null ? null : category.getValue(),
                cursor,
                limit
        );

        var response = new AdminEventPage()
                .data(page.data().stream()
                        .map(item -> new AdminEventSummary()
                                .id(item.id())
                                .title(item.title())
                                .category(EventCategory.fromValue(
                                        item.category()
                                ))
                                .status(EventStatus.fromValue(
                                        item.status()
                                ))
                                .posterMobileUrl(item.posterMobileUrl())
                                .posterDesktopUrl(item.posterDesktopUrl())
                                .createdAt(item.createdAt())
                                .updatedAt(item.updatedAt()))
                        .toList())
                .nextCursor(page.nextCursor());

        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> putPriceTiers(
            UUID eventId,
            List<PriceTierInput> input
    ) {
        if (input == null
                || input.stream().anyMatch(Objects::isNull)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "Danh sách hạng giá không hợp lệ."
            );
        }

        var changes = input.stream()
                .map(item -> new PriceTierChange(
                        item.getId(),
                        item.getName(),
                        item.getPrice(),
                        item.getColor()
                ))
                .toList();

        eventPricing.putPriceTiers(eventId, changes);

        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> putZonePrices(
            UUID eventId,
            List<ZonePriceInput> input
    ) {
        if (input == null
                || input.stream().anyMatch(Objects::isNull)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "Danh sách giá khu không hợp lệ."
            );
        }

        var changes = input.stream()
                .map(item -> new ZonePriceChange(
                        item.getZoneId(),
                        item.getPriceTierId()
                ))
                .toList();

        eventPricing.putZonePrices(eventId, changes);

        return ResponseEntity.ok().build();
    }
    @Override
    public ResponseEntity<Void> blockSeats(UUID id, BlockSeatsRequest input) {
        seatBlocking.block(id, input.getSeatIds(), input.getReason());
        return ResponseEntity.ok().build();
    }
    @Override
    public ResponseEntity<Void> unblockSeats(UUID id, UnblockSeatsRequest input) {
        seatBlocking.unblock(id, input.getSeatIds());
        return ResponseEntity.ok().build();
    }
    @Override
    public ResponseEntity<EventDetail> publishEvent(UUID eventId) {
        publishing.publish(eventId);
        return ResponseEntity.ok(toAdminEventDetail(eventService.getDetails(eventId)));
    }
}
