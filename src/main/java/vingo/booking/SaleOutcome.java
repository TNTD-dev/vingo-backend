package vingo.booking;

/**
 * Kết quả của {@link BookingService#markSold}, không phải chuỗi trạng thái đơn trong DB.
 * {@code SOLD}: tồn kho đã bán và đơn đã PAID, người gọi được tiếp tục phát vé;
 * {@code NEEDS_REVIEW}: đơn đã NEEDS_REVIEW do thiếu tồn kho, không phát vé.
 */
public enum SaleOutcome {
    SOLD, NEEDS_REVIEW
}
