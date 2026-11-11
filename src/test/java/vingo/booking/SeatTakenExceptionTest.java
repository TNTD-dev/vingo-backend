package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vingo.shared.error.ErrorCode;

class SeatTakenExceptionTest {

    @Test
    void seatTakenException_containsSeatTakenCodeAndExactSeatLists() {
        List<UUID> takenSeatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<UUID> suggestedSeatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        SeatTakenException exception = new SeatTakenException(takenSeatIds, suggestedSeatIds);

        assertThat(exception.code()).isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(exception.properties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "taken_seat_ids", takenSeatIds,
                "suggested_seat_ids", suggestedSeatIds));
    }
}
