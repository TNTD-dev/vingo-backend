package vingo.search;

import java.util.List;

/**
 * Một trang kết quả. {@code hasMore} cho biết còn trang sau (đã lấy {@code limit + 1} dòng để biết).
 * {@code matchMode} là chế độ khớp đã dùng cho trang này, cursor của trang sau phải ghi lại đúng chế độ đó.
 */
public record Page<T>(List<T> items, boolean hasMore, MatchMode matchMode) {

    public Page {
        items = List.copyOf(items);
        matchMode = matchMode == null ? MatchMode.STRICT : matchMode;
    }

    public static <T> Page<T> empty() {
        return new Page<>(List.of(), false, MatchMode.STRICT);
    }
}
