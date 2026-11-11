package vingo.search;

/**
 * Trạng thái ghế khi đọc. {@code AVAILABLE} gồm cả ghế HELD đã quá hạn giữ (Implicit Status). Ghế thuộc layout nhưng
 * chưa có dòng {@code showtime_seat} được báo {@code BLOCKED}.
 */
public enum SeatState {
    AVAILABLE,
    HELD,
    SOLD,
    BLOCKED
}
