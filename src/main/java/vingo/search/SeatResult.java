package vingo.search;

import java.util.Optional;
import java.util.UUID;

/** Một ghế trong sơ đồ. {@code row} là {@code row_label}, {@code seatType} là mã loại ghế (STANDARD, COUPLE, WHEELCHAIR). */
public record SeatResult(
        UUID seatId,
        String row,
        int number,
        String seatType,
        Optional<UUID> pairSeatId,
        SeatState status,
        int x,
        int y) {
}
