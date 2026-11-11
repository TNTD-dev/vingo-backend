package vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import vingo.shared.error.ErrorCode;
import vingo.shared.ratelimit.RateLimitedException;
import vingo.shared.ratelimit.RateLimiter;

class RateLimiterTest {

    private final UUID userId = UUID.randomUUID();
    private final String key = "rl:holds:" + userId + ":0";
    private final Duration window = Duration.ofMinutes(1);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @Test
    void check_allowsRequest_whenRedisIncrementFails() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(key)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatCode(() -> limiterAt(0).check(userId, "holds", 10, window)).doesNotThrowAnyException();
    }

    @Test
    void check_allowsRequest_whenRedisExpireFails() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(key)).thenReturn(1L);
        when(redis.expire(key, window)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatCode(() -> limiterAt(0).check(userId, "holds", 10, window)).doesNotThrowAnyException();
    }

    @Test
    void check_expiresCounterOnFirstRequest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(key)).thenReturn(1L);

        limiterAt(0).check(userId, "holds", 10, window);

        verify(redis).expire(key, window);
    }

    @ParameterizedTest
    @CsvSource({"0, 60", "15250, 45", "59999, 1"})
    void check_throwsRateLimitedWithRemainingSeconds_whenLimitExceeded(long elapsedMillis, long retryAfter) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(key)).thenReturn(11L);

        assertThatThrownBy(() -> limiterAt(elapsedMillis).check(userId, "holds", 10, window))
                .isInstanceOfSatisfying(RateLimitedException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(exception.retryAfter()).isEqualTo(retryAfter);
                });
    }

    private RateLimiter limiterAt(long elapsedMillis) {
        return new RateLimiter(redis, Clock.fixed(Instant.EPOCH.plusMillis(elapsedMillis), ZoneOffset.UTC));
    }
}
