package vingo.booking.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mang dữ liệu gợi ý ra khỏi transaction giữ ghế đã thất bại. */
public class SeatHoldConflict extends RuntimeException {

    private final List<UUID> takenSeatIds;
    private final Map<UUID, Integer> requestedByZone;

    public SeatHoldConflict(List<UUID> takenSeatIds, Map<UUID, Integer> requestedByZone) {
        this.takenSeatIds = List.copyOf(takenSeatIds);
        this.requestedByZone = Collections.unmodifiableMap(new LinkedHashMap<>(requestedByZone));
    }

    public List<UUID> takenSeatIds() {
        return takenSeatIds;
    }

    public Map<UUID, Integer> requestedByZone() {
        return requestedByZone;
    }
}
