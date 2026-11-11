package vingo.search;

import java.time.OffsetDateTime;
import java.util.UUID;

import vingo.catalog.ShowtimeStatus;

/**
 * Một suất diễn. {@code status} tính khi đọc bằng {@link ShowtimeStatus#of}. {@code soldOut} là true khi không còn
 * ghế trống (tính cả ghế HELD đã quá hạn là trống) và khu đứng hết chỗ.
 */
public record ShowtimeResult(
        UUID id,
        UUID eventId,
        UUID hallId,
        UUID seatLayoutId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime saleOpensAt,
        OffsetDateTime saleClosesAt,
        ShowtimeStatus status,
        boolean soldOut) {
}
