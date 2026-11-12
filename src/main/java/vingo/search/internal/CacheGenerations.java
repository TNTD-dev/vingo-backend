package vingo.search.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Bộ đếm "thế hệ" trong Redis để một lần nạp cache bắt đầu trước khi catalog đổi không ghi đè dữ liệu cũ sau khi
 * đã xoá.
 *
 * <p>Khoá cache gồm id và thế hệ hiện tại, thế hệ được đọc <b>trước khi</b> đọc DB. Sau commit, listener tăng thế
 * hệ: lần nạp cũ ghi vào khoá thế hệ cũ, không ai đọc nữa và tự hết hạn theo TTL. Bộ đếm có TTL dài hơn TTL cache
 * nên không phình Redis. Redis lỗi lúc đọc thế hệ thì trả khoá ngẫu nhiên (luôn miss, đọc thẳng DB).
 */
@Component("cacheGenerations")
@Profile("api")
public class CacheGenerations {

    private static final Logger log = LoggerFactory.getLogger(CacheGenerations.class);

    private static final String PREFIX = "vingo:search:gen:";
    private static final String VENUE = PREFIX + "venue";
    /** Dài hơn TTL cache (10 phút) để khi bộ đếm hết hạn thì mọi khoá cũ đã hết hạn từ trước. */
    private static final Duration GENERATION_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;

    CacheGenerations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Khoá cache của event và danh sách suất: {@code eventId:g<thế hệ event>:v<thế hệ sân>}. */
    public String eventKey(UUID eventId) {
        try {
            List<String> gens = redis.opsForValue().multiGet(List.of(eventCounter(eventId), VENUE));
            return eventId + ":g" + gen(gens, 0) + ":v" + gen(gens, 1);
        } catch (RuntimeException ex) {
            return unreadable(ex);
        }
    }

    /** Khoá cache layout của suất: {@code showtimeId:g<thế hệ suất>}. */
    public String showtimeKey(UUID showtimeId) {
        try {
            List<String> gens = redis.opsForValue().multiGet(List.of(showtimeCounter(showtimeId)));
            return showtimeId + ":g" + gen(gens, 0);
        } catch (RuntimeException ex) {
            return unreadable(ex);
        }
    }

    void bumpEvent(UUID eventId) {
        bump(eventCounter(eventId));
    }

    void bumpShowtime(UUID showtimeId) {
        bump(showtimeCounter(showtimeId));
    }

    void bumpVenues() {
        bump(VENUE);
    }

    private void bump(String counter) {
        try {
            redis.opsForValue().increment(counter);
            redis.expire(counter, GENERATION_TTL);
        } catch (RuntimeException ex) {
            log.warn("Không tăng được thế hệ cache search, chờ TTL op=bump error={}", ex.getClass().getSimpleName());
        }
    }

    private static String gen(List<String> values, int index) {
        String value = values == null ? null : values.get(index);
        return value == null ? "0" : value;
    }

    private static String unreadable(RuntimeException ex) {
        log.warn("Không đọc được thế hệ cache search, bỏ qua cache và đọc DB error={}", ex.getClass().getSimpleName());
        return "nocache:" + UUID.randomUUID();
    }

    private static String eventCounter(UUID eventId) {
        return PREFIX + "event:" + eventId;
    }

    private static String showtimeCounter(UUID showtimeId) {
        return PREFIX + "showtime:" + showtimeId;
    }
}
