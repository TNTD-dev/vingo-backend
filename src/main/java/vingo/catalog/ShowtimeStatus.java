package vingo.catalog;

import java.time.OffsetDateTime;

/**
 * Trạng thái của một suất diễn, tính khi đọc.
 *
 * <p>DB chỉ lưu {@code DRAFT}, {@code SCHEDULED}, {@code CANCELLED}. Ba trạng thái còn lại
 * ({@code ON_SALE}, {@code SALES_CLOSED}, {@code FINISHED}) suy ra từ {@code sale_opens_at},
 * {@code sale_closes_at}, {@code ends_at} và giờ hiện tại, nên không bao giờ ghi xuống DB.
 *
 * <p>Hàm {@link #of} là hàm thuần, không đụng DB, dùng chung cho search và booking. Search dùng để
 * trả {@code status} của suất; booking dùng để ném {@code SHOWTIME_NOT_ON_SALE} khi suất chưa phải
 * {@code ON_SALE}. Chỗ gọi truyền {@code now} lấy từ {@code Clock}, không gọi {@code now()} trong hàm.
 */
public enum ShowtimeStatus {
    DRAFT,
    SCHEDULED,
    ON_SALE,
    SALES_CLOSED,
    FINISHED,
    CANCELLED;

    /**
     * Tính trạng thái hiện hành của suất.
     *
     * <ul>
     *   <li>DB lưu {@code CANCELLED} hoặc {@code DRAFT}: giữ nguyên, không xét giờ.</li>
     *   <li>{@code now < saleOpensAt}: {@code SCHEDULED}.</li>
     *   <li>{@code saleOpensAt <= now < saleClosesAt}: {@code ON_SALE}.</li>
     *   <li>{@code saleClosesAt <= now < endsAt}: {@code SALES_CLOSED}.</li>
     *   <li>{@code now >= endsAt}: {@code FINISHED}.</li>
     * </ul>
     *
     * @param storedStatus giá trị cột {@code showtime.status} (DRAFT, SCHEDULED hoặc CANCELLED)
     * @throws IllegalArgumentException khi {@code storedStatus} không thuộc ba giá trị trên
     */
    public static ShowtimeStatus of(String storedStatus, OffsetDateTime saleOpensAt, OffsetDateTime saleClosesAt,
            OffsetDateTime endsAt, OffsetDateTime now) {
        switch (storedStatus) {
            case "CANCELLED":
                return CANCELLED;
            case "DRAFT":
                return DRAFT;
            case "SCHEDULED":
                break;
            default:
                throw new IllegalArgumentException("Trạng thái suất lưu trong DB không hợp lệ: " + storedStatus);
        }
        if (now.isBefore(saleOpensAt)) {
            return SCHEDULED;
        }
        if (now.isBefore(saleClosesAt)) {
            return ON_SALE;
        }
        if (now.isBefore(endsAt)) {
            return SALES_CLOSED;
        }
        return FINISHED;
    }
}
