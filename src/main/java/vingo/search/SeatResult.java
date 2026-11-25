package vingo.search;

import java.util.UUID;

/** Một ghế trong sơ đồ kèm lựa chọn bán (sku), hạng vé (ticket_type) và giá hiệu lực. {@code row} là {@code row_label}. */
public record SeatResult(
        UUID seatId,
        UUID skuId,
        UUID ticketTypeId,
        String tier,
        Long price,
        String row,
        int number,
        SeatState status) {

    public SeatResult(UUID seatId, String row, int number, SeatState status) {
        this(seatId, null, null, null, null, row, number, status);
    }
}
