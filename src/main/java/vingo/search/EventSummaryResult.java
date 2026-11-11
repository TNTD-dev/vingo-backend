package vingo.search;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Một thẻ sự kiện trong kết quả tìm kiếm.
 *
 * <p>{@code city} là mã thành phố lưu trong {@code venue.city}. {@code priceFrom} null khi sự kiện chưa có hạng nào gán khu.
 * {@code sortKey} là khoá sắp xếp của dòng này, dùng để sinh cursor.
 */
public record EventSummaryResult(
        UUID id,
        String title,
        String category,
        String posterUrl,
        String venueName,
        String city,
        OffsetDateTime firstStartsAt,
        Long priceFrom,
        boolean onSale,
        String sortKey) {
}
