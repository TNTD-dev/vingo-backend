package vingo.search;

import java.util.List;
import java.util.UUID;

import vingo.catalog.ShowtimeStatus;

/** Sơ đồ ghế của một suất: layout tĩnh ghép với trạng thái động. {@code status} tính theo giờ hiện tại. */
public record SeatmapResult(UUID showtimeId, ShowtimeStatus status, List<SeatmapZoneResult> zones) {
}
