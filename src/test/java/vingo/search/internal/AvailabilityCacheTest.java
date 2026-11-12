package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vingo.search.AvailabilityResult;
import vingo.search.SearchService;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** Nhiều request cùng miss cache availability chỉ truy vấn DB một lần ({@code @Cacheable(sync = true)}). */
class AvailabilityCacheTest extends IntegrationTest {

    private static final int THREADS = 24;

    @Autowired
    private SearchService searchService;
    @MockitoSpyBean
    private SeatmapRepository seatmaps;

    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
    }

    @Test
    void getAvailability_queriesDbOnce_whenConcurrentMisses() throws Exception {
        var created = fixtures.event()
                .showtime(s -> s.onSale().seated(5))
                .showtime(s -> s.onSale().seated(1))
                .create();
        UUID showtime = created.showtimeIds().get(0);
        // Làm nóng kết nối Redis và serializer bằng suất khác, để lần nạp đầu không vượt timeout 200ms của Redis.
        searchService.getAvailability(created.showtimeIds().get(1));
        reset(seatmaps);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AvailabilityResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return searchService.getAvailability(showtime);
                }));
            }
            start.countDown();
            for (Future<AvailabilityResult> f : results) {
                assertThat(f.get().zones().getFirst().seatsLeft()).isEqualTo(5);
            }
        }

        verify(seatmaps, times(1)).countZones(any(), any());
    }
}
