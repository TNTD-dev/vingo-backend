package vingo.search;

import java.util.Optional;
import java.util.UUID;

/** Số chỗ còn của một khu. {@code tier} rỗng khi khu chưa gán hạng giá. */
public record ZoneAvailabilityResult(
        UUID zoneId,
        String code,
        String name,
        UUID skuId,
        Optional<PriceTierResult> tier,
        int seatsLeft) {

    public ZoneAvailabilityResult(UUID zoneId, String code, String name, Optional<PriceTierResult> tier, int seatsLeft) {
        this(zoneId, code, name, null, tier, seatsLeft);
    }
}
