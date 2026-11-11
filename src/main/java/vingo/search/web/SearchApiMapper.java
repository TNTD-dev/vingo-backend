package vingo.search.web;

import java.net.URI;
import java.util.Map;

import org.springframework.stereotype.Component;

import vingo.api.model.CityCode;
import vingo.api.model.EventCategory;
import vingo.api.model.EventSummary;
import vingo.search.EventSummaryResult;

/** Chuyển kết quả của {@code SearchService} sang model sinh từ contract. */
@Component
class SearchApiMapper {

    // TẠM: tên hiển thị thành phố map ở search, chờ catalog chốt danh sách và tên (CityCode trong yaml cũng là danh sách tạm).
    private static final Map<String, String> CITY_NAMES = Map.of(
            "HCM", "TP. Hồ Chí Minh",
            "HN", "Hà Nội",
            "DN", "Đà Nẵng");

    EventSummary toEventSummary(EventSummaryResult r) {
        EventSummary summary = new EventSummary()
                .id(r.id())
                .title(r.title())
                .category(EventCategory.fromValue(r.category()))
                .venueName(r.venueName())
                .firstStartsAt(r.firstStartsAt())
                .onSale(r.onSale());
        if (r.posterUrl() != null) {
            summary.posterUrl(URI.create(r.posterUrl()));
        }
        if (r.priceFrom() != null) {
            summary.priceFrom(r.priceFrom());
        }
        String cityName = r.city() == null ? null : CITY_NAMES.get(r.city());
        if (cityName != null) {
            summary.city(CityCode.fromValue(r.city())).cityName(cityName);
        } else if (r.city() != null) {
            // Mã chưa có trong danh sách tạm: không ép vào enum, trả nguyên giá trị làm tên hiển thị.
            summary.cityName(r.city());
        }
        return summary;
    }
}
