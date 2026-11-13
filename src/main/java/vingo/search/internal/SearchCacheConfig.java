package vingo.search.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import vingo.search.AvailabilityResult;
import vingo.search.internal.EventDetailRepository.ShowtimeRow;
import vingo.search.internal.SeatmapRepository.SeatmapLayout;

/**
 * Cache đọc của search trên Redis (chỉ profile {@code api}). Redis không phải nguồn sự thật: lỗi Redis chỉ ghi
 * WARN rồi đọc thẳng DB.
 *
 * <p>Mỗi cache có serializer JSON gắn với đúng một kiểu, không bật default typing nên không đọc được lớp tuỳ ý
 * từ Redis. Khoá có tiền tố {@code vingo:search:<tên cache>:}.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@Profile("api")
class SearchCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheConfig.class);

    /** Dữ liệu thô của chi tiết sự kiện (sự kiện, bảng giá, các suất). Khoá: eventId. */
    static final String EVENT = "search-event";
    /** Danh sách suất thô của sự kiện. Khoá: eventId. */
    static final String SHOWTIMES = "search-showtimes";
    /** Layout tĩnh của sơ đồ ghế. Khoá: showtimeId. */
    static final String SEATMAP_LAYOUT = "search-seatmap-layout";
    /** Kết quả availability. Khoá: showtimeId. */
    static final String AVAILABILITY = "search-availability";

    static final Duration STATIC_TTL = Duration.ofMinutes(10);
    static final Duration AVAILABILITY_TTL = Duration.ofSeconds(3);

    private static final String KEY_PREFIX = "vingo:search:";

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        JsonMapper mapper = JsonMapper.builder().build();
        JavaType showtimes = mapper.getTypeFactory().constructCollectionType(List.class, ShowtimeRow.class);
        Map<String, RedisCacheConfiguration> caches = new LinkedHashMap<>();
        caches.put(EVENT, config(mapper, mapper.constructType(EventCore.class), STATIC_TTL));
        caches.put(SHOWTIMES, config(mapper, showtimes, STATIC_TTL));
        caches.put(SEATMAP_LAYOUT, config(mapper, mapper.constructType(SeatmapLayout.class), STATIC_TTL));
        caches.put(AVAILABILITY, config(mapper, mapper.constructType(AvailabilityResult.class), AVAILABILITY_TTL));
        // Xoá theo tiền tố bằng SCAN, không dùng KEYS để khỏi chặn Redis.
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory,
                BatchStrategies.scan(1000));
        return new SingleFlightCacheManager(writer, caches);
    }

    private static RedisCacheConfiguration config(JsonMapper mapper, JavaType type, Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(name -> KEY_PREFIX + name + ":")
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(mapper, type)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /** Nuốt mọi lỗi cache (get/put/evict/clear), ghi WARN; request vẫn đọc DB và trả đúng. */
    static final class LoggingCacheErrorHandler extends SimpleCacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
            warn("get", cache, ex);
        }

        @Override
        public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
            warn("put", cache, ex);
        }

        @Override
        public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
            warn("evict", cache, ex);
        }

        @Override
        public void handleCacheClearError(RuntimeException ex, Cache cache) {
            warn("clear", cache, ex);
        }

        private static void warn(String op, Cache cache, RuntimeException ex) {
            log.warn("Cache search lỗi, bỏ qua và đọc DB op={} cache={} error={}", op, cache.getName(),
                    ex.getClass().getSimpleName());
        }
    }

    /** Tạo cache Redis có {@code get(key, loader)} chạy một lần cho mỗi khoá trong cùng JVM. */
    static final class SingleFlightCacheManager extends RedisCacheManager {

        SingleFlightCacheManager(RedisCacheWriter writer, Map<String, RedisCacheConfiguration> configs) {
            super(writer, RedisCacheConfiguration.defaultCacheConfig(), false, configs);
        }

        @Override
        protected RedisCache createRedisCache(String name, RedisCacheConfiguration config) {
            return new SingleFlightRedisCache(name, getCacheWriter(),
                    config != null ? config : getDefaultCacheConfiguration());
        }
    }

    /**
     * {@code @Cacheable(sync = true)} chỉ chống dồn request khi cache tự khoá trong lúc nạp, mà writer không khoá
     * của Redis thì không làm vậy. Lớp này gom các request cùng miss một khoá trong JVM: request đầu truy vấn DB,
     * các request sau chờ và nhận đúng kết quả đó. Hai instance api vẫn có thể mỗi bên nạp một lần; không dùng
     * khoá phân tán để Redis chậm không làm kẹt request.
     */
    static final class SingleFlightRedisCache extends RedisCache {

        private final ConcurrentHashMap<Object, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

        SingleFlightRedisCache(String name, RedisCacheWriter writer, RedisCacheConfiguration config) {
            super(name, writer, config);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            ValueWrapper hit = get(key);
            if (hit != null) {
                return (T) hit.get();
            }
            CompletableFuture<Object> mine = new CompletableFuture<>();
            CompletableFuture<Object> running = inFlight.putIfAbsent(key, mine);
            if (running != null) {
                return (T) await(key, valueLoader, running);
            }
            try {
                // Request khác có thể đã nạp xong và nhả inFlight sau lần đọc cache miss phía trên.
                try {
                    hit = get(key);
                } catch (RuntimeException ex) {
                    log.warn("Cache search lỗi khi đọc lại, tiếp tục nạp dữ liệu cache={} error={}",
                            getName(), ex.getClass().getSimpleName());
                }
                if (hit != null) {
                    T value = (T) hit.get();
                    mine.complete(value);
                    return value;
                }
                T value;
                try {
                    value = valueLoader.call();
                } catch (Exception ex) {
                    mine.completeExceptionally(ex);
                    throw new ValueRetrievalException(key, valueLoader, ex);
                }
                try {
                    put(key, value);
                    // Đọc lại để chắc giá trị đã hiển thị với request đến sau khi nhả chỗ này.
                    get(key);
                } catch (RuntimeException ex) {
                    // Đã tải xong: lỗi Redis chỉ làm mất cache, vẫn trả giá trị.
                    log.warn("Cache search lỗi, bỏ qua và trả dữ liệu đã tải op=put cache={} error={}", getName(),
                            ex.getClass().getSimpleName());
                }
                mine.complete(value);
                return value;
            } finally {
                // Chặn người chờ treo mãi nếu có Error bay qua.
                mine.completeExceptionally(new IllegalStateException("Nạp cache không hoàn tất"));
                inFlight.remove(key, mine);
            }
        }

        private static Object await(Object key, Callable<?> valueLoader, CompletableFuture<Object> running) {
            try {
                return running.get();
            } catch (ExecutionException ex) {
                throw new ValueRetrievalException(key, valueLoader, ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ValueRetrievalException(key, valueLoader, ex);
            }
        }
    }
}
