package vingo.shared.ratelimit;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Báo vượt giới hạn request, kèm số giây chờ để gửi lại. */
public class RateLimitedException extends DomainException {

    private final long retryAfter;

    public RateLimitedException(long retryAfter) {
        super(ErrorCode.RATE_LIMITED, "Gọi quá nhanh, vui lòng thử lại sau");
        this.retryAfter = Math.max(1, retryAfter);
    }

    public long retryAfter() {
        return retryAfter;
    }
}
