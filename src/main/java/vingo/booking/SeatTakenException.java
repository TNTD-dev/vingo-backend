package vingo.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/**
 * Không giữ đủ ghế do ghế đang được giữ, đã bán hoặc bị khoá: {@code SEAT_TAKEN} (HTTP 409).
 * Ném ra khỏi transaction giữ chỗ để rollback cả đơn, kể cả các ghế đã giữ được.
 * Thuộc tính lỗi gồm {@code taken_seat_ids} và {@code suggested_seat_ids};
 * gợi ý có thể rỗng và không đảm bảo ghế vẫn trống khi người gọi thử lại.
 */
public class SeatTakenException extends DomainException {

    /**
     * Tạo lỗi với bản sao bất biến của hai danh sách ghế.
     *
     * @param takenSeatIds các ghế không giữ được
     * @param suggestedSeatIds các ghế thay thế gợi ý, có thể rỗng
     */
    public SeatTakenException(List<UUID> takenSeatIds, List<UUID> suggestedSeatIds) {
        super(ErrorCode.SEAT_TAKEN, ErrorCode.SEAT_TAKEN.title(), Map.of(
                "taken_seat_ids", List.copyOf(takenSeatIds),
                "suggested_seat_ids", List.copyOf(suggestedSeatIds)));
    }
}
