package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import vingo.search.internal.SearchCacheConfig.SingleFlightRedisCache;

/** Lỗi Redis sau khi đã nạp xong không được biến thành lỗi của request. */
class SingleFlightRedisCacheTest {

    private final RedisCacheWriter writer = mock(RedisCacheWriter.class);
    private final SingleFlightRedisCache cache =
            new SingleFlightRedisCache("search-test", writer, RedisCacheConfiguration.defaultCacheConfig());

    @Test
    void get_returnsLoadedValue_whenRedisFailsOnPut() {
        doThrow(new IllegalStateException("Redis ghi lỗi")).when(writer).put(any(), any(), any(), any());

        String value = cache.get("k", () -> "đã tải");

        assertThat(value).isEqualTo("đã tải");
    }

    @Test
    void get_throwsValueRetrievalException_whenLoaderFails() {
        assertThatThrownBy(() -> cache.get("k", () -> {
            throw new IllegalArgumentException("loader lỗi");
        })).isInstanceOf(ValueRetrievalException.class).hasRootCauseMessage("loader lỗi");
    }

    @Test
    void get_loadsValue_whenCacheRecheckFails() {
        var concurrentCache = spy(cache);
        doReturn(null).doThrow(new IllegalStateException("Redis đọc lại lỗi")).doReturn(null)
                .when(concurrentCache).get("k");
        var loads = new AtomicInteger();
        assertThat(concurrentCache.get("k", () -> {
            loads.incrementAndGet();
            return "đã tải";
        })).isEqualTo("đã tải");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void get_rechecksCache_whenAnotherRequestFinishedAfterInitialMiss() throws Exception {
        var concurrentCache = spy(cache);
        var stored = new AtomicReference<ValueWrapper>();
        var pauseFirstRead = new AtomicBoolean(true);
        var firstMiss = new CountDownLatch(1);
        var resumeFirstRequest = new CountDownLatch(1);
        var loads = new AtomicInteger();
        Callable<String> loader = () -> {
            loads.incrementAndGet();
            return "đã tải";
        };
        doAnswer(invocation -> {
            ValueWrapper snapshot = stored.get();
            if (pauseFirstRead.compareAndSet(true, false)) {
                firstMiss.countDown();
                if (!resumeFirstRequest.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Không nhận được tín hiệu tiếp tục request đầu.");
                }
            }
            return snapshot;
        }).when(concurrentCache).get("k");
        doAnswer(invocation -> {
            stored.set(new SimpleValueWrapper(invocation.getArgument(1)));
            return null;
        }).when(concurrentCache).put(any(), any());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> concurrentCache.get("k", loader));
            try {
                assertThat(firstMiss.await(10, TimeUnit.SECONDS)).isTrue();
                // Request thứ hai hoàn tất nạp và nhả inFlight khi request đầu còn giữ cache miss cũ.
                assertThat(concurrentCache.get("k", loader)).isEqualTo("đã tải");
            } finally {
                resumeFirstRequest.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("đã tải");
            assertThat(loads.get()).isEqualTo(1);
        }
    }
}
