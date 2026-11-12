package vingo.search;

import java.util.Optional;
import java.util.UUID;

/** Số chỗ còn của một khu. {@code tier} rỗng khi khu chưa gán hạng giá. */
public record ZoneAvailabilityResult(UUID zoneId, String name, Optional<PriceTierResult> tier, int seatsLeft) {
}
