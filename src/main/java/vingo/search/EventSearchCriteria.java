package vingo.search;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Điều kiện tìm sự kiện. Constructor kiểm tra tham số và chốt kiểu sắp xếp mặc định.
 *
 * @param q        từ khoá, null hoặc rỗng là không lọc theo từ khoá
 * @param category FOOTBALL, CONCERT, THEATER, OTHER
 * @param city     mã thành phố (HCM, HN, DN)
 * @param from     suất bắt đầu từ mốc này
 * @param to       suất bắt đầu tới mốc này
 * @param sort     null thì có {@code q} dùng RELEVANCE, không có thì dùng STARTS_AT
 * @param after    dòng cuối của trang trước (keyset), null là trang đầu
 */
public record EventSearchCriteria(
        String q,
        String category,
        String city,
        OffsetDateTime from,
        OffsetDateTime to,
        Long priceMin,
        Long priceMax,
        Sort sort,
        int limit,
        After after) {

    public static final int MAX_LIMIT = 100;

    public enum Sort {
        RELEVANCE("relevance"),
        STARTS_AT("starts_at"),
        PRICE("price"),
        POPULARITY("popularity");

        private final String value;

        Sort(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** Đọc giá trị query param; null là chưa chọn. */
        public static Sort parse(String value) {
            if (value == null) {
                return null;
            }
            for (Sort s : values()) {
                if (s.value.equals(value)) {
                    return s;
                }
            }
            throw new InvalidSearchRequestException("sort", "sort phải là relevance, starts_at, price hoặc popularity");
        }
    }

    /** Khoá sắp xếp (dạng chuỗi) và id của dòng cuối trang trước. */
    public record After(String key, UUID id) {
    }

    public EventSearchCriteria {
        q = q == null || q.isBlank() ? null : q.strip();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidSearchRequestException("limit", "limit phải từ 1 đến " + MAX_LIMIT);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidSearchRequestException("from", "from phải trước hoặc bằng to");
        }
        if (priceMin != null && priceMin < 0) {
            throw new InvalidSearchRequestException("price_min", "price_min phải >= 0");
        }
        if (priceMax != null && priceMax < 0) {
            throw new InvalidSearchRequestException("price_max", "price_max phải >= 0");
        }
        if (priceMin != null && priceMax != null && priceMin > priceMax) {
            throw new InvalidSearchRequestException("price_min", "price_min phải nhỏ hơn hoặc bằng price_max");
        }
        if (sort == Sort.RELEVANCE && q == null) {
            throw new InvalidSearchRequestException("sort", "relevance chỉ dùng khi có q");
        }
        if (sort == null) {
            sort = q != null ? Sort.RELEVANCE : Sort.STARTS_AT;
        }
    }

    public EventSearchCriteria withAfter(After after) {
        return new EventSearchCriteria(q, category, city, from, to, priceMin, priceMax, sort, limit, after);
    }

    /** Chuỗi mô tả bộ lọc và sort, cursor gắn với chuỗi này. Không gồm {@code limit} và {@code after}. */
    public String filterSignature() {
        return String.join("\u001f",
                sort.value(),
                q == null ? "" : q,
                category == null ? "" : category,
                city == null ? "" : city,
                from == null ? "" : from.toInstant().toString(),
                to == null ? "" : to.toInstant().toString(),
                priceMin == null ? "" : priceMin.toString(),
                priceMax == null ? "" : priceMax.toString());
    }
}
