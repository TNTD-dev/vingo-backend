package vingo.booking;

import java.util.UUID;

/**
 * Dòng đơn cho module phát vé; chỉ dùng phát vé sau khi booking chốt bán thành công.
 *
 * @param orderItemId id dòng đơn để gắn vé
 * @param showtimeId suất diễn
 * @param zoneId khu ngồi hoặc khu đứng
 * @param seatId id ghế ngồi; null với khu đứng
 * @param quantity số vé của dòng: 1 với ghế ngồi, số chỗ đã đặt với khu đứng
 */
public record OrderLine(UUID orderItemId, UUID showtimeId, UUID zoneId, UUID seatId, int quantity) {}
