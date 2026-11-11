package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache.ValueRetrievalException;
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
}
