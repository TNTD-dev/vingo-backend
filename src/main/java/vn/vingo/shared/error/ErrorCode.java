package vn.vingo.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Mã lỗi trả trong trường {@code code} của ProblemDetail.
 *
 * <p>Phải khớp enum {@code Problem.code} trong {@code contracts/vingo-openapi.yaml} và bảng mã lỗi trong CLAUDE.md.
 * Thêm mã mới: sửa yaml trước.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Không có quyền"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Sai thông tin đăng nhập"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Tài khoản bị khoá tạm"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email đã tồn tại"),
    PHONE_TAKEN(HttpStatus.CONFLICT, "Số điện thoại đã tồn tại"),
    OTP_INVALID(HttpStatus.BAD_REQUEST, "OTP không đúng"),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST, "OTP đã hết hạn"),
    OTP_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "Nhập sai OTP quá số lần"),
    SEAT_TAKEN(HttpStatus.CONFLICT, "Ghế vừa bị giữ hoặc bán"),
    NOT_ENOUGH_STOCK(HttpStatus.CONFLICT, "Khu đứng không đủ chỗ"),
    SHOWTIME_NOT_ON_SALE(HttpStatus.CONFLICT, "Suất chưa mở hoặc đã đóng bán"),
    ORDER_STATE_INVALID(HttpStatus.CONFLICT, "Trạng thái đơn không cho phép thao tác này"),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "Đơn đã quá hạn giữ ghế"),
    PROFILE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Chưa có hồ sơ người mua"),
    MAX_TICKETS_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Vượt số vé tối đa mỗi đơn"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Trùng Idempotency-Key nhưng khác nội dung"),
    TICKET_NOT_VALID(HttpStatus.CONFLICT, "Vé không còn hiệu lực"),
    TICKET_USED(HttpStatus.CONFLICT, "Vé đã được sử dụng"),
    VENUE_IN_USE(HttpStatus.CONFLICT, "Sân còn suất chưa kết thúc"),
    EVENT_LOCKED(HttpStatus.CONFLICT, "Sự kiện đã có suất đang bán"),
    EVENT_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "Sự kiện thiếu suất, giá hoặc hạng"),
    SHOWTIME_LOCKED(HttpStatus.CONFLICT, "Suất đã mở bán, không sửa được"),
    ACTION_STATE_INVALID(HttpStatus.CONFLICT, "Thao tác đã hết hạn hoặc đã xử lý"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Gọi quá nhanh");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
