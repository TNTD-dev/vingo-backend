package vingo.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EventDetails(
        Header header,
        List<Tier> priceTiers,
        List<Session> showtimes,
        LayoutSelection selection,
        List<ZonePriceChange> zonePrices,
        int skuCount
) {
    public EventDetails(
            Header header,
            List<Tier> priceTiers,
            List<Session> showtimes,
            LayoutSelection selection,
            List<ZonePriceChange> zonePrices
    ) {
        this(header, priceTiers, showtimes, selection, zonePrices, 0);
    }

    public record LayoutSelection(UUID venueId, UUID layoutId) {}

    public record ZoneSkuPreviewResult(
            UUID eventId,
            int totalSkuCount,
            int seatedSkuCount,
            int standingSkuCount,
            List<ZoneSkuItemResult> zones
    ) {}

    public record ZoneSkuItemResult(
            UUID zoneId,
            String zoneName,
            String kind,
            UUID ticketTypeId,
            String ticketTypeName,
            long price,
            int skuCount
    ) {}

    public record Header(
            UUID id,
            String status,
            boolean visible,
            EventService.CreateEvent data
    ) {}

    public record SkuPriceRow(
            UUID id,
            UUID skuId,
            long price,
            OffsetDateTime effectiveFrom,
            UUID createdBy,
            String reason,
            OffsetDateTime createdAt
    ) {}

    public record Tier(
            UUID id,
            String name,
            long price,
            String color,
            List<SkuPriceRow> prices
    ) {}

    public record VenueInfo(
            UUID id,
            String name,
            String address,
            String city
    ) {}

    public record Session(
            UUID id,
            UUID eventId,
            UUID seatLayoutId,
            String eventStatus,
            OffsetDateTime cancelledAt,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime saleOpensAt,
            OffsetDateTime saleClosesAt,
            VenueInfo venue
    ) {}
}