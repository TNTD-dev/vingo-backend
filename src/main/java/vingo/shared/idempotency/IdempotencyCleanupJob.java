package vingo.shared.idempotency;

import java.time.Clock;
import java.time.OffsetDateTime;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Profile worker mỗi giờ xoá key quá 24 giờ theo lô 1000, dùng ShedLock {@code idempotency_cleanup}. */
@Component
@Profile("worker")
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final int BATCH = 1000;

    private final JdbcClient jdbc;
    private final Clock clock;

    public IdempotencyCleanupJob(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "idempotency_cleanup", lockAtMostFor = "PT1H")
    public void cleanup() {
        var cutoff = OffsetDateTime.now(clock).minusHours(24);
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM idempotency_key WHERE id IN (
                                SELECT id FROM idempotency_key WHERE created_at < :cutoff
                                ORDER BY created_at, id LIMIT :batch
                            )
                            """)
                    .param("cutoff", cutoff).param("batch", BATCH).update();
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Deleted {} expired idempotency keys", total);
        }
    }
}
