package vingo.search;

import java.util.List;

/**
 * Một trang kết quả. {@code hasMore} cho biết còn trang sau (đã lấy {@code limit + 1} dòng để biết).
 */
public record Page<T>(List<T> items, boolean hasMore) {

    public Page {
        items = List.copyOf(items);
    }

    public static <T> Page<T> empty() {
        return new Page<>(List.of(), false);
    }
}
