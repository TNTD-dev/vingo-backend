package vingo.search.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import vingo.catalog.CatalogChanged;
import vingo.catalog.VenueChanged;

/**
 * Xoá cache của search khi catalog thay đổi. Chạy <b>sau commit</b> của transaction đã phát event, trong cùng
 * thread, nên khi request ghi của admin trả về thì cache đã sạch; transaction rollback thì không xoá.
 *
 * <p>Lỗi Redis chỉ ghi WARN: TTL 10 phút là lưới an toàn.
 */
@Component
@Profile("api")
class CatalogChangedListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogChangedListener.class);

    private final CacheManager caches;

    CatalogChangedListener(CacheManager caches) {
        this.caches = caches;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CatalogChanged event) {
        String eventId = event.eventId().toString();
        evict(SearchCacheConfig.EVENT, eventId);
        evict(SearchCacheConfig.SHOWTIMES, eventId);
        event.showtimeIds().forEach(id -> evict(SearchCacheConfig.SEATMAP_LAYOUT, id.toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(VenueChanged event) {
        clear(SearchCacheConfig.EVENT);
        clear(SearchCacheConfig.SHOWTIMES);
    }

    private void evict(String name, String key) {
        try {
            Cache cache = caches.getCache(name);
            if (cache != null) {
                cache.evict(key);
            }
        } catch (RuntimeException ex) {
            log.warn("Không xoá được cache search, chờ TTL op=evict cache={} error={}", name,
                    ex.getClass().getSimpleName());
        }
    }

    private void clear(String name) {
        try {
            Cache cache = caches.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        } catch (RuntimeException ex) {
            log.warn("Không xoá được cache search, chờ TTL op=clear cache={} error={}", name,
                    ex.getClass().getSimpleName());
        }
    }
}
