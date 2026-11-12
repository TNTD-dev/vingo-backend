package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.catalog.CatalogChanged;
import vingo.search.SearchService;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** Lần nạp cache bắt đầu trước khi catalog đổi không được ghi đè dữ liệu cũ sau khi đã xoá. */
class StaleLoadCacheTest extends IntegrationTest {

    @Autowired
    private SearchService searchService;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager txManager;
    @MockitoSpyBean
    private EventDetailRepository details;

    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
    }

    @Test
    void getEvent_staleLoadAfterEvict_isNotServed() throws Exception {
        UUID warmup = fixtures.event().title("Làm nóng").create().id();
        searchService.getEvent(warmup);
        UUID eventId = fixtures.event().title("Tên cũ").create().id();

        CountDownLatch readOldData = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        // Lần nạp đầu của eventId đọc DB (dữ liệu cũ) rồi dừng lại, chưa kịp ghi cache.
        doAnswer(invocation -> {
            Object row = invocation.callRealMethod();
            if (eventId.equals(invocation.getArgument(0)) && paused.compareAndSet(false, true)) {
                readOldData.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return row;
        }).when(details).findPublished(any());

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<String> stale = pool.submit(() -> searchService.getEvent(eventId).title());
            assertThat(readOldData.await(10, TimeUnit.SECONDS)).isTrue();

            // Admin sửa và commit trong lúc lần nạp cũ còn dở.
            jdbc.sql("UPDATE event SET title = 'Tên mới' WHERE id = :id").param("id", eventId).update();
            new TransactionTemplate(txManager).executeWithoutResult(
                    status -> publisher.publishEvent(new CatalogChanged(eventId, Set.of())));
            release.countDown();

            assertThat(stale.get(10, TimeUnit.SECONDS)).isEqualTo("Tên cũ");
        }

        // Lần nạp cũ vừa ghi vào khoá thế hệ cũ; lần đọc sau phải thấy dữ liệu mới.
        assertThat(searchService.getEvent(eventId).title()).isEqualTo("Tên mới");
    }
}
