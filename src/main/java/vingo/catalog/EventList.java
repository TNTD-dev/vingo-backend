package vingo.catalog;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EventList(List<Item> data, String nextCursor) {

    public EventList {
        data = List.copyOf(data);
    }

    public record Position(OffsetDateTime createdAt, UUID id) {}

    public record Item(
            UUID id,
            String title,
            String category,
            String status,
            URI posterMobileUrl,
            URI posterDesktopUrl,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
            ) {}
}
