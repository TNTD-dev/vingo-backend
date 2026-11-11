package vingo.shared.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Giới hạn theo cửa sổ cố định (fixed window), Redis lỗi thì cho qua. */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Đếm và kiểm request theo {@code userId + bucket} trong cửa sổ cố định (fixed window),
     * chia theo thời gian epoch từ Clock. Mỗi lần gọi đều tăng bộ đếm, kể cả lần vượt giới hạn.
     * Redis ném lỗi khi tăng bộ đếm/đặt TTL thì cho request đi tiếp.
     *
     * @param userId người dùng cần giới hạn
     * @param bucket tên nhóm thao tác dùng chung bộ đếm
     * @param limit số lần gọi tối đa được phép trong một cửa sổ, phải dương
     * @param window độ dài cửa sổ, ít nhất 1 mili giây
     * @throws RateLimitedException với {@code RATE_LIMITED} (HTTP 429) nếu số lần gọi vượt limit,
     *         kèm số giây còn lại đến cửa sổ mới, làm tròn lên
     * @throws IllegalArgumentException nếu limit không dương hoặc window dưới 1 mili giây
     */
    public void check(UUID userId, String bucket, int limit, Duration window) {
        long windowMillis = window.toMillis();
        if (limit < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("Limit và cửa sổ phải dương");
        }
        long now = clock.millis();
        String key = "rl:" + bucket + ":" + userId + ":" + Math.floorDiv(now, windowMillis);
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (Long.valueOf(1).equals(count)) {
                redis.expire(key, window);
            }
        } catch (RuntimeException ex) {
            log.warn("Redis rate limit không khả dụng ({}), cho phép request tiếp tục",
                    ex.getClass().getSimpleName());
            return;
        }
        if (count != null && count > limit) {
            long remainingMillis = windowMillis - Math.floorMod(now, windowMillis);
            throw new RateLimitedException((remainingMillis - 1) / 1000 + 1);
        }
    }
}
