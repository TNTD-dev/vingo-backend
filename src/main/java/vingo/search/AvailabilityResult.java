package vingo.search;

import java.util.List;
import java.util.UUID;

import vingo.catalog.ShowtimeStatus;

/** Số chỗ còn theo khu. {@code soldOut} là true khi mọi khu có {@code seatsLeft} bằng 0. */
public record AvailabilityResult(UUID showtimeId, ShowtimeStatus status, boolean soldOut,
        List<ZoneAvailabilityResult> zones) {
}
