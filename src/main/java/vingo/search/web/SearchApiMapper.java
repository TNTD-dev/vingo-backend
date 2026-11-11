package vingo.search.web;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import vingo.api.model.CityCode;
import vingo.api.model.EventCategory;
import vingo.api.model.EventDetail;
import vingo.api.model.EventStatus;
import vingo.api.model.EventSummary;
import vingo.api.model.PriceTier;
import vingo.api.model.Showtime;
import vingo.api.model.ShowtimeStatus;
import vingo.api.model.Venue;
import vingo.search.EventDetailResult;
import vingo.search.EventSummaryResult;
import vingo.search.PriceTierResult;
import vingo.search.ShowtimeResult;
import vingo.search.VenueResult;

/** Chuyển kết quả của {@code SearchService} sang model sinh từ contract. */
@Component
class SearchApiMapper {

    private static final Logger log = LoggerFactory.getLogger(SearchApiMapper.class);

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
            URI poster = parsePoster(r.posterUrl(), r.id());
            if (poster != null) {
                summary.posterUrl(poster);
            }
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

    EventDetail toEventDetail(EventDetailResult r) {
        EventDetail detail = new EventDetail()
                .id(r.id())
                .title(r.title())
                .description(r.description())
                .category(EventCategory.fromValue(r.category()))
                .status(EventStatus.fromValue(r.status()))
                .attributes(r.attributes())
                .maxTicketsPerOrder(r.maxTicketsPerOrder())
                .priceTiers(r.priceTiers().stream().map(this::toPriceTier).toList())
                .showtimes(r.showtimes().stream().map(this::toShowtime).toList());
        URI poster = r.posterUrl() == null ? null : parsePoster(r.posterUrl(), r.id());
        if (poster != null) {
            // Catalog chỉ lưu một poster_url: dùng cho cả bản mobile và desktop.
            detail.posterMobileUrl(poster).posterDesktopUrl(poster);
        }
        r.venue().ifPresent(v -> detail.venue(toVenue(v)));
        return detail;
    }

    Showtime toShowtime(ShowtimeResult r) {
        return new Showtime()
                .id(r.id())
                .eventId(r.eventId())
                .hallId(r.hallId())
                .seatLayoutId(r.seatLayoutId())
                .startsAt(r.startsAt())
                .endsAt(r.endsAt())
                .saleOpensAt(r.saleOpensAt())
                .saleClosesAt(r.saleClosesAt())
                .status(ShowtimeStatus.fromValue(r.status().name()))
                .soldOut(r.soldOut())
                .venue(toVenue(r.venue()));
    }

    /** Đọc URI poster; sai cú pháp thì bỏ trống poster và ghi WARN, không làm hỏng cả response. */
    private static URI parsePoster(String url, UUID eventId) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("poster_url sai cú pháp, bỏ qua poster event_id={}", eventId);
            return null;
        }
    }

    private PriceTier toPriceTier(PriceTierResult r) {
        return new PriceTier().id(r.id()).name(r.name()).price(r.price()).color(r.color());
    }

    private Venue toVenue(VenueResult r) {
        Venue venue = new Venue()
                .id(r.id())
                .name(r.name())
                .type(Venue.TypeEnum.fromValue(r.type()))
                .address(r.address());
        String cityName = r.city() == null ? null : CITY_NAMES.get(r.city());
        if (cityName != null) {
            venue.city(CityCode.fromValue(r.city())).cityName(cityName);
        } else if (r.city() != null) {
            venue.cityName(r.city());
        }
        return venue;
    }
}
