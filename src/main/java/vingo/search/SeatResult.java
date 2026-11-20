package vingo.search;

import java.util.UUID;

/** Một ghế trong sơ đồ. {@code row} là {@code row_label}. */
public record SeatResult(
        UUID seatId,
        String row,
        int number,
        SeatState status) {
}
