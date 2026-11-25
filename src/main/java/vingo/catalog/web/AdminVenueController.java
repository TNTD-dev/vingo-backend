package vingo.catalog.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import vingo.api.model.EventCategory;
import vingo.api.model.EventCreateInput;
import vingo.api.model.EventDetail;
import vingo.api.model.EventPatchInput;
import vingo.api.model.EventStatus;
import vingo.api.model.ListVenues200Response;
import vingo.api.model.PriceTier;
import vingo.api.model.PriceTierInput;
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
import vingo.api.model.SkuPrice;
import vingo.api.model.SkuPriceInput;
import vingo.api.model.Venue;
import vingo.api.model.VenueCreateInput;
import vingo.api.model.VenueInput;
import vingo.api.model.ZonePriceInput;
import vingo.api.model.ZoneSkuPreview;
import vingo.api.model.ZoneSkuPreviewItem;
import vingo.catalog.EventDetails;
import vingo.shared.security.CurrentUser;
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
    private final CurrentUser currentUser;

    AdminVenueController(
            VenueService venues,
            SeatLayoutImportService layouts,
            JsonMapper json,
            Clock clock,
            EventService eventService,
            EventPricingService eventPricing,
            SeatBlockingService seatBlocking,
            EventPublishingService publishing,
            CurrentUser currentUser
    ) {
        this.venues = venues;
        this.layouts = layouts;
        this.json = json;
        this.clock = clock;
        this.eventService = eventService;
        this.eventPricing = eventPricing;
        this.seatBlocking = seatBlocking;
        this.publishing = publishing;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<Venue> createVenue(VenueCreateInput input) {
        VenueRow created = venues.create(new VenueData(
                input.getName(),
                input.getAddress(),
                input.getCity(),
                input.getDistrict(),
                input.getLat(),
                input.getLng(),
                input.getRules()
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(created));
    }

    @Override
    public ResponseEntity<EventDetail> createEvent(EventCreateInput input) {
        var created = eventService.create(new EventService.CreateEvent(
                input.getTitle(),
                input.getDescription(),
                input.getCategory() == null ? null : input.getCategory().getValue(),
                input.getHomeTeam(),
                input.getAwayTeam(),
                input.getLeague(),
                input.getRound(),
                input.getPosterMobileUrl(),
                input.getPosterDesktopUrl(),
                input.getMaxTicketsPerOrder()
        ), input.getSeatLayoutId());

        return ResponseEntity.status(HttpStatus.CREATED).body(toAdminEventDetail(eventService.getDetails(created.id())));
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

        var data = page.data().stream().map(this::toApi).toList();
        return ResponseEntity.ok(new ListVenues200Response()
                .data(data)
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<Venue> getAdminVenue(UUID venueId) {
        return ResponseEntity.ok(toApi(venues.get(venueId)));
    }

    @Override
    public ResponseEntity<Venue> updateVenue(UUID venueId, VenueInput input) {
        VenueRow updated = venues.update(venueId, new VenueData(
                input.getName(),
                input.getAddress(),
                input.getCity(),
                input.getDistrict(),
                input.getLat(),
                input.getLng(),
                input.getRules()
        ));

        return ResponseEntity.ok(toApi(updated));
    }

    @Override
    public ResponseEntity<Void> deleteVenue(UUID venueId) {
        venues.delete(venueId);
        return ResponseEntity.noContent().build();
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

    private static String cityName(String city) {
        return switch (city) {
            case null -> null;
            case "HCM" -> "TP. Hồ Chí Minh";
            case "HN" -> "Hà Nội";
            case "DN" -> "Đà Nẵng";
            default -> city;
        };
    }

    private Venue toApi(VenueRow row) {
        VenueData data = row.data();
        return new Venue()
                .id(row.id())
                .name(data.name())
                .address(data.address())
                .city(data.city())
                .cityName(cityName(data.city()))
                .district(data.district())
                .lat(data.lat())
                .lng(data.lng())
                .rules(data.rules())
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .layoutCount(row.layoutCount())
                .publishedLayoutCount(row.layoutCount())
                .draftLayoutCount(row.draftLayoutCount());
    }

    @Override
    public ResponseEntity<SeatLayoutOverview> getSeatLayout(UUID layoutId) {
        var overview = layouts.getOverview(layoutId);
        var header = overview.header();
        return ResponseEntity.ok(new SeatLayoutOverview()
                .id(header.id())
                .venueId(header.venueId())
                .overviewId(header.overviewId())
                .layoutType(header.layoutType() == null ? null : vingo.api.model.LayoutType.fromValue(header.layoutType()))
                .name(header.name())
                .zoneCount(header.zoneCount())
                .seatCount(header.seatCount())
                .version(header.version())
                .createdAt(header.createdAt())
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
                .kind(SeatLayoutZoneDetail.KindEnum.fromValue(zone.kind().name()))
                .capacity(zone.capacity())
                .rows(detail.rows() == null ? null : detail.rows().stream()
                        .map(row -> new SeatLayoutZoneDetailAllOfRows()
                                .rowLabel(row.rowLabel())
                                .seats(row.seats().stream()
                                        .map(seat -> new SeatLayoutZoneDetailAllOfSeats()
                                                .id(seat.id())
                                                .number(seat.number()))
                                        .toList()))
                        .toList());
        return ResponseEntity.ok(response);
    }

    private static SeatLayoutZone toZone(ZoneSummaryRow zone) {
        return new SeatLayoutZone()
                .id(zone.id())
                .code(zone.code())
                .label(zone.label())
                .kind(SeatLayoutZone.KindEnum.fromValue(zone.kind().name()))
                .capacity(zone.capacity());
    }

    private static SeatLayoutBounds toBounds(Bounds bounds) {
        if (bounds == null) {
            return null;
        }
        return new SeatLayoutBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    @Override
    public ResponseEntity<SeatLayoutPage> listSeatLayouts(UUID venueId, vingo.api.model.LayoutType layoutType, String cursor, Integer limit) {
        var page = layouts.listByVenue(venueId, layoutType == null ? null : layoutType.getValue(), cursor, limit);
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
                input.getTitle(), input.getDescription(), input.getCategory(),
                input.getHomeTeam(), input.getAwayTeam(), input.getLeague(), input.getRound(),
                input.getPosterMobileUrl(), input.getPosterDesktopUrl(),
                input.getMaxTicketsPerOrder()
        ), input.getSeatLayoutId());
        return ResponseEntity.ok(toAdminEventDetail(result));
    }

    private EventDetail toAdminEventDetail(EventDetails result) {
        var header = result.header();
        var data = header.data();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        var priceTiers = result.priceTiers().stream()
                .map(tier -> new PriceTier()
                        .id(tier.id())
                        .ticketTypeId(tier.id())
                        .name(tier.name())
                        .price(tier.price())
                        .color(tier.color())
                        .prices(tier.prices().stream().map(AdminVenueController::toSkuPrice).toList()))
                .toList();

        return new EventDetail()
                .seatLayoutId(result.selection() == null ? null : result.selection().layoutId())
                .zonePrices(result.zonePrices().stream().map(price -> new ZonePriceInput()
                        .zoneId(price.zoneId())
                        .priceTierId(price.priceTierId())
                        .ticketTypeId(price.priceTierId())
                        .displayName(price.displayName())).toList())
                .id(header.id())
                .status(EventStatus.fromValue(header.status()))
                .title(data.title())
                .description(data.description())
                .category(EventCategory.fromValue(data.category()))
                .homeTeam(data.homeTeam())
                .awayTeam(data.awayTeam())
                .league(data.league())
                .round(data.round())
                .posterMobileUrl(data.posterMobileUrl())
                .posterDesktopUrl(data.posterDesktopUrl())
                .maxTicketsPerOrder(data.maxTicketsPerOrder())
                .priceTiers(priceTiers)
                .ticketTypes(priceTiers)
                .skuCount(result.skuCount())
                .showtimes(result.showtimes().stream()
                        .map(session -> toAdminShowtime(session, now))
                        .toList());
    }

    private static SkuPrice toSkuPrice(EventDetails.SkuPriceRow p) {
        return new SkuPrice()
                .id(p.id())
                .skuId(p.skuId())
                .ticketTypeId(p.skuId())
                .price(p.price())
                .effectiveFrom(p.effectiveFrom())
                .createdBy(p.createdBy())
                .reason(p.reason())
                .createdAt(p.createdAt());
    }

    private static SeatLayout toSeatLayout(LayoutHeaderRow header) {
        return new SeatLayout()
                .id(header.id())
                .venueId(header.venueId())
                .overviewId(header.overviewId())
                .layoutType(header.layoutType() == null ? null : vingo.api.model.LayoutType.fromValue(header.layoutType()))
                .name(header.name())
                .zoneCount(header.zoneCount())
                .seatCount(header.seatCount())
                .version(header.version())
                .createdAt(header.createdAt());
    }

    private static ShowtimeData toShowtimeData(ShowtimeInput input) {
        return new ShowtimeData(
                input.getSeatLayoutId(),
                input.getStartsAt(),
                input.getEndsAt(),
                input.getSaleOpensAt(),
                input.getSaleClosesAt()
        );
    }

    private Showtime toShowtime(ShowtimeRow row) {
        var data = row.data();
        String eventStatus = eventService.getDetails(row.eventId()).header().status();
        return new Showtime()
                .id(row.id())
                .eventId(row.eventId())
                .status(ShowtimeStatus.fromValue(vingo.catalog.ShowtimeStatus.of(
                        eventStatus, row.cancelledAt(),
                        data.saleOpensAt(), data.saleClosesAt(), data.endsAt(),
                        OffsetDateTime.now(clock)).name()))
                .cancelledAt(row.cancelledAt())
                .seatLayoutId(data.seatLayoutId())
                .startsAt(data.startsAt())
                .endsAt(data.endsAt())
                .saleOpensAt(data.saleOpensAt())
                .saleClosesAt(data.saleClosesAt());
    }

    private Showtime toAdminShowtime(EventDetails.Session session, OffsetDateTime now) {
        var status = vingo.catalog.ShowtimeStatus.of(
                session.eventStatus(),
                session.cancelledAt(),
                session.saleOpensAt(),
                session.saleClosesAt(),
                session.endsAt(),
                now
        );

        return new Showtime()
                .id(session.id())
                .eventId(session.eventId())
                .seatLayoutId(session.seatLayoutId())
                .status(ShowtimeStatus.fromValue(status.name()))
                .cancelledAt(session.cancelledAt())
                .startsAt(session.startsAt())
                .endsAt(session.endsAt())
                .saleOpensAt(session.saleOpensAt())
                .saleClosesAt(session.saleClosesAt())
                .venue(toAdminEventVenue(session.venue()));
    }

    private Venue toAdminEventVenue(EventDetails.VenueInfo source) {
        return new Venue()
                .id(source.id())
                .name(source.name())
                .address(source.address())
                .city(source.city())
                .cityName(cityName(source.city()));
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
    public ResponseEntity<ZoneSkuPreview> putZonePrices(
            UUID eventId,
            List<ZonePriceInput> input
    ) {
        var changes = toZonePriceChanges(input);
        var preview = eventPricing.putZonePrices(eventId, changes);
        return ResponseEntity.ok(toZoneSkuPreview(preview));
    }

    @Override
    public ResponseEntity<ZoneSkuPreview> previewZonePrices(
            UUID eventId,
            List<ZonePriceInput> input
    ) {
        var changes = toZonePriceChanges(input);
        var preview = eventPricing.previewZonePrices(eventId, changes);
        return ResponseEntity.ok(toZoneSkuPreview(preview));
    }

    private static List<ZonePriceChange> toZonePriceChanges(List<ZonePriceInput> input) {
        if (input == null
                || input.stream().anyMatch(Objects::isNull)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "Danh sách giá khu không hợp lệ."
            );
        }
        return input.stream()
                .map(item -> {
                    if (item.getTicketTypeId() != null && item.getPriceTierId() != null
                            && !item.getTicketTypeId().equals(item.getPriceTierId())) {
                        throw new DomainException(ErrorCode.VALIDATION_ERROR,
                                "ticket_type_id và price_tier_id phải cùng giá trị.");
                    }
                    UUID tierId = item.getTicketTypeId() != null ? item.getTicketTypeId() : item.getPriceTierId();
                    return new ZonePriceChange(
                            item.getZoneId(),
                            tierId,
                            item.getDisplayName()
                    );
                })
                .toList();
    }

    private static ZoneSkuPreview toZoneSkuPreview(EventDetails.ZoneSkuPreviewResult preview) {
        return new ZoneSkuPreview()
                .eventId(preview.eventId())
                .totalSkuCount(preview.totalSkuCount())
                .seatedSkuCount(preview.seatedSkuCount())
                .standingSkuCount(preview.standingSkuCount())
                .zones(preview.zones().stream()
                        .map(z -> new ZoneSkuPreviewItem()
                                .zoneId(z.zoneId())
                                .zoneName(z.zoneName())
                                .kind(ZoneSkuPreviewItem.KindEnum.fromValue(z.kind()))
                                .ticketTypeId(z.ticketTypeId())
                                .ticketTypeName(z.ticketTypeName())
                                .price(z.price())
                                .skuCount(z.skuCount()))
                        .toList());
    }
    @Override
    public ResponseEntity<Void> blockSeats(UUID id, BlockSeatsRequest input) {
        seatBlocking.block(id, input.getSeatIds(), input.getReason(), currentUser.require().id());
        return ResponseEntity.ok().build();
    }
    @Override
    public ResponseEntity<Void> unblockSeats(UUID id, UnblockSeatsRequest input) {
        seatBlocking.unblock(id, input.getSeatIds(), currentUser.require().id());
        return ResponseEntity.ok().build();
    }
    @Override
    public ResponseEntity<EventDetail> publishEvent(UUID eventId) {
        publishing.publish(eventId);
        return ResponseEntity.ok(toAdminEventDetail(eventService.getDetails(eventId)));
    }

    @Override
    public ResponseEntity<Void> cancelEvent(UUID id, vingo.api.model.CancelEventRequest request) {
        publishing.cancelEvent(id, currentUser.require().id(), request.getReason());
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> cancelShowtime(UUID id, vingo.api.model.CancelEventRequest request) {
        publishing.cancelShowtime(id, currentUser.require().id(), request.getReason());
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<SkuPrice> addSkuPrice(UUID eventId, UUID tierId, SkuPriceInput input) {
        var created = eventPricing.addSkuPrice(
                eventId, tierId, input.getPrice(), input.getEffectiveFrom(), input.getReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(toSkuPrice(created));
    }
}
