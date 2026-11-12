package vingo.search;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Một khu trong sơ đồ. Khu SEATED có {@code seats} (có thể rỗng) và {@code seatsLeft} bằng 0 (không dùng).
 * Khu STANDING có {@code seatsLeft} và {@code seats} rỗng. {@code tier} rỗng khi khu chưa gán hạng giá.
 */
public record SeatmapZoneResult(
        UUID zoneId,
        String name,
        ZoneKind kind,
        Optional<PriceTierResult> tier,
        int seatsLeft,
        List<SeatResult> seats) {
}
