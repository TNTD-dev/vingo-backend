package vingo.reporting;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MetricQuery(String metric, UUID eventId, UUID showtimeId, UUID priceTierId,
        OffsetDateTime from, OffsetDateTime to, String groupBy) {}
