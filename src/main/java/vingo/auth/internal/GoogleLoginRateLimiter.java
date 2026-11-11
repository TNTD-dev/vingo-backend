package vingo.auth.internal;

import java.util.List;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.Assert;

/** Giới hạn bắt đầu đăng nhập Google theo IP, dùng chung giữa các replica API. */
public final class GoogleLoginRateLimiter {

    private static final String KEY_PREFIX = "vingo:auth:google:start:";
    private static final RedisScript<Long> WINDOW = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], 60)
            end
            if count <= tonumber(ARGV[1]) then
                return 0
            end
            return math.max(1, redis.call('TTL', KEYS[1]))
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String limit;

    /** Nhận Redis dùng chung và số lượt tối đa cho mỗi IP trong một phút. */
    public GoogleLoginRateLimiter(StringRedisTemplate redis, int perMinute) {
        Assert.isTrue(perMinute > 0, "Google login rate limit must be positive");
        this.redis = redis;
        this.limit = Integer.toString(perMinute);
    }

    /** Trả 0 khi cho phép, hoặc số giây còn lại của cửa sổ khi vượt giới hạn. */
    public long retryAfter(String remoteAddress) {
        Long remaining = redis.execute(WINDOW, List.of(KEY_PREFIX + remoteAddress), limit);
        if (remaining == null) {
            throw new DataRetrievalFailureException("Missing Google login rate limit result");
        }
        return remaining;
    }
}
