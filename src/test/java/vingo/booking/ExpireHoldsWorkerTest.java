package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.support.CatalogFixture;
import vingo.support.MutableClock;
import vingo.support.TestClockConfig;
import vingo.support.TestcontainersConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true", "vingo.jobs.expire-holds=PT1S"
})
@ActiveProfiles({"worker", "test"})
@Import({TestcontainersConfig.class, TestClockConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpireHoldsWorkerTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactions;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    @Timeout(30)
    void worker_expiresOverdueOrderAndReleasesHeldSeatWithoutCallingJobDirectly() throws Exception {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        var catalog = new CatalogFixture(jdbc, clock);
        record HeldOrder(UUID order, UUID seat) {}
        var held = new TransactionTemplate(transactions).execute(status -> {
            var showtime = catalog.onSaleShowtime();
            var past = catalog.now().minusMinutes(1);
            UUID order = catalog.order(catalog.buyer(), showtime.id(), "PENDING", past);
            UUID seat = showtime.event().seat(1);
            catalog.setSeat(showtime.id(), seat, "HELD", order, past);
            return new HeldOrder(order, seat);
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean expired;
        do {
            expired = jdbc.sql("""
                            SELECT o.status = 'EXPIRED' AND ss.status = 'AVAILABLE'
                                   AND ss.order_id IS NULL AND ss.held_until IS NULL
                            FROM orders o JOIN showtime_seat ss ON ss.showtime_id = o.showtime_id
                            WHERE o.id = :order AND ss.seat_id = :seat
                            """)
                    .param("order", held.order()).param("seat", held.seat()).query(Boolean.class).single();
            if (expired) {
                break;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);

        assertThat(expired).as("Scheduled worker expires the order and releases its seat within 20s").isTrue();
    }
}
