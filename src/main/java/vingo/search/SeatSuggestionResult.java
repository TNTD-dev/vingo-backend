package vingo.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Nhóm ghế đủ số lượng yêu cầu, hoặc rỗng nếu thiếu ghế. Giá tính bằng VND. */
public record SeatSuggestionResult(UUID showtimeId, UUID zoneId, int quantity, List<Seat> seats,
        boolean together, int seatsLeft, Long unitPrice, OffsetDateTime observedAt) {

    public record Seat(UUID seatId, String row, int number, int rowRank) {}

    public Long total() {
        return unitPrice == null || seats.isEmpty() ? null : Math.multiplyExact(unitPrice, seats.size());
    }
}
