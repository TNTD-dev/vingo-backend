package vingo.search.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vingo.shared.ratelimit.RateLimiter;

/**
 * Giới hạn số request tới các endpoint search theo IP client, dùng chung một bộ đếm cho cả 5 endpoint.
 *
 * <p>Là interceptor (không phải servlet filter) để {@code RateLimitedException} đi qua
 * {@code GlobalExceptionHandler} và thành 429 {@code RATE_LIMITED} kèm {@code Retry-After}. Redis lỗi thì
 * {@link RateLimiter} cho request đi tiếp.
 */
@Component
class SearchRateLimitInterceptor implements HandlerInterceptor {

    private static final String BUCKET = "search";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter rateLimiter;
    private final int perMinute;

    SearchRateLimitInterceptor(RateLimiter rateLimiter,
            @Value("${vingo.search.rate-limit.per-minute:120}") int perMinute) {
        this.rateLimiter = rateLimiter;
        this.perMinute = perMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.getBeanType() == SearchController.class) {
            rateLimiter.check(clientKey(request.getRemoteAddr()), BUCKET, perMinute, WINDOW);
        }
        return true;
    }

    /** {@link RateLimiter} đếm theo UUID nên IP được băm thành UUID ổn định (không lưu IP thô trong khoá Redis). */
    static UUID clientKey(String remoteAddress) {
        return UUID.nameUUIDFromBytes(remoteAddress.getBytes(StandardCharsets.UTF_8));
    }
}
