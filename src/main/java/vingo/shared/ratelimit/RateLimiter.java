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
