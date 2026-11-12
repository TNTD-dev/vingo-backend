package vingo.shared.ratelimit;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/**
 * Lỗi {@code RATE_LIMITED} (HTTP 429) khi vượt giới hạn request.
 * GlobalExceptionHandler dùng {@link #retryAfter()} làm header {@code Retry-After}, đơn vị giây,
 * để người gọi biết khi nào có thể thử lại.
 */
public class RateLimitedException extends DomainException {

    private final long retryAfter;

    /**
     * Tạo lỗi vượt giới hạn với thời gian chờ tối thiểu 1 giây.
     *
     * @param retryAfter số giây cần chờ; giá trị dưới 1 được nâng thành 1
     */
    public RateLimitedException(long retryAfter) {
        super(ErrorCode.RATE_LIMITED, "Gọi quá nhanh, vui lòng thử lại sau");
        this.retryAfter = Math.max(1, retryAfter);
    }

    public long retryAfter() {
        return retryAfter;
    }
}
