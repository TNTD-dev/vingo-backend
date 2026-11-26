package vingo.search;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Số chỗ còn của một khu. {@code tier} rỗng khi khu chưa gán hạng giá. */
public record ZoneAvailabilityResult(
        UUID zoneId,
        String code,
        String name,
        ZoneKind kind,
        UUID skuId,
        Optional<PriceTierResult> tier,
        int seatsLeft) {

    public ZoneAvailabilityResult {
        Objects.requireNonNull(kind, "kind");
    }
}
