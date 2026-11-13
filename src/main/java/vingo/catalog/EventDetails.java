package vingo.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EventDetails(
        Header header,
        List<Tier> priceTiers,
        List<Session> showtimes
) {
    public record Header(
            UUID id,
            String status,
            EventService.CreateEvent data
    ) {}

    public record Tier(
            UUID id,
            String name,
            long price,
            String color
    ) {}

    public record VenueInfo(
            UUID id,
            String name,
            String type,
            String address,
            String city
    ) {}

    public record Session(
            UUID id,
            UUID eventId,
            UUID hallId,
            UUID seatLayoutId,
            String storedStatus,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime saleOpensAt,
            OffsetDateTime saleClosesAt,
            VenueInfo venue
    ) {}
}