package vingo.booking;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Dữ liệu đơn để payment đối chiếu sau khi {@link BookingService#lockOrderForPayment} khoá dòng.
 * Trạng thái là giá trị DB, không tự chuyển PENDING quá hạn thành EXPIRED.
 *
 * @param id id đơn
 * @param userId chủ đơn
 * @param showtimeId suất diễn của đơn
 * @param status chuỗi DB: PENDING, PAID, EXPIRED, CANCELLED, NEEDS_REVIEW hoặc REFUNDED
 * @param totalAmount tổng tiền đơn, số nguyên VND để đối chiếu tiền thanh toán
 * @param expiresAt thời điểm hết hạn giữ chỗ
 */
public record OrderForPayment(UUID id, UUID userId, UUID showtimeId, String status,
                              long totalAmount, OffsetDateTime expiresAt) {}
