package vingo.auth.internal;

import java.time.Clock;
import java.time.OffsetDateTime;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Profile worker mỗi giờ xoá phiên hết hạn theo lô 1000, dùng ShedLock {@code session_cleanup}. */
@Component
@Profile("worker")
public class SessionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupJob.class);
    private static final int BATCH = 1000;

    private final JdbcClient jdbc;
    private final Clock clock;

    public SessionCleanupJob(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "session_cleanup", lockAtMostFor = "PT1H")
    public void cleanup() {
        var now = OffsetDateTime.now(clock);
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM login_session WHERE id IN (
                                SELECT id FROM login_session WHERE expires_at <= :now
                                ORDER BY expires_at, id LIMIT :batch FOR UPDATE SKIP LOCKED
                            )
                            """)
                    .param("now", now).param("batch", BATCH).update();
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Deleted {} expired login sessions", total);
        }
        do {
            deleted = jdbc.sql("""
                    DELETE FROM user_device WHERE (user_id, device_hash) IN (
                        SELECT d.user_id, d.device_hash FROM user_device d JOIN user_account u ON u.id = d.user_id
                        WHERE d.last_seen_at < :cutoff ORDER BY d.last_seen_at
                        LIMIT :batch FOR UPDATE OF u, d SKIP LOCKED
                    )
                    """).param("cutoff", now.minusYears(1)).param("batch", BATCH).update();
        } while (deleted == BATCH);
    }
}
