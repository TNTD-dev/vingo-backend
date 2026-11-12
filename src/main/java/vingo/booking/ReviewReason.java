package vingo.booking;

/**
 * Lý do đơn cần xử lý thủ công; {@link Enum#name()} được lưu nguyên văn vào {@code orders.review_reason}.
 * {@code PAID_AFTER_EXPIRY}: tiền về sau hạn hoặc markSold phát hiện thiếu tồn kho;
 * {@code PAID_AFTER_CANCEL}: tiền về sau huỷ; {@code AMOUNT_MISMATCH}: số tiền không khớp tổng đơn.
 */
public enum ReviewReason {
    PAID_AFTER_EXPIRY, PAID_AFTER_CANCEL, AMOUNT_MISMATCH
}
