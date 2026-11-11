package vingo.search;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chi tiết một sự kiện PUBLISHED.
 *
 * <p>{@code venue} là địa điểm của suất sắp tới sớm nhất, không có thì của suất đầu tiên; rỗng khi sự kiện chưa có
 * suất nào. {@code posterUrl}, {@code description} có thể null. {@code attributes} là thuộc tính theo loại sự kiện.
 */
public record EventDetailResult(
        UUID id,
        String title,
        String description,
        String category,
        String status,
        String posterUrl,
        Map<String, Object> attributes,
        int maxTicketsPerOrder,
        Optional<VenueResult> venue,
        List<PriceTierResult> priceTiers,
        List<ShowtimeResult> showtimes) {
}
