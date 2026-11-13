package vingo.booking;

import java.util.UUID;

/**
 * Sự kiện được phát đồng bộ trong transaction của {@link BookingService#cancelOrder},
 * sau khi đơn đã chuyển sang CANCELLED và nhả tồn kho, trước commit.
 * Payment có thể dùng listener đồng bộ để đóng intent OPEN trong cùng transaction;
 * lỗi không được xử lý từ listener làm rollback cả lần huỷ.
 *
 * @param orderId đơn vừa huỷ
 * @param userId chủ đơn
 */
public record OrderCancelled(UUID orderId, UUID userId) {}
