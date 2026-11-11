package vingo.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Ghế vừa bị giữ/bán → 409 SEAT_TAKEN, rollback cả đơn; kèm gợi ý ghế khác. */
public class SeatTakenException extends DomainException {

    public SeatTakenException(List<UUID> takenSeatIds, List<UUID> suggestedSeatIds) {
        super(ErrorCode.SEAT_TAKEN, ErrorCode.SEAT_TAKEN.title(), Map.of(
                "taken_seat_ids", List.copyOf(takenSeatIds),
                "suggested_seat_ids", List.copyOf(suggestedSeatIds)));
    }
}
