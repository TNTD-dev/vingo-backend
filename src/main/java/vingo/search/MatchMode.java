package vingo.search;

/**
 * Chế độ khớp từ khoá. {@code STRICT} bắt buộc mọi từ trong {@code q} phải có; {@code LOOSE} chỉ cần một phần
 * các từ (nới ra khi khớp chặt không có kết quả ở trang đầu).
 */
public enum MatchMode {
    STRICT,
    LOOSE
}
