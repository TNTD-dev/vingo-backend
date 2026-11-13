package vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import vingo.shared.idempotency.IdempotencyCleanupJob;
import vingo.support.IntegrationTest;

class IdempotencyCleanupJobTest extends IntegrationTest {

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void cleanup_deletesAllExpiredBatchesAndKeepsBoundaryAndFreshKeys() {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        UUID user = catalog.buyer();
        jdbc.sql("""
                        INSERT INTO idempotency_key (user_id, endpoint, key, request_hash, created_at)
                        SELECT :user, 'cleanup-test', n::text, 'hash', :old
                        FROM generate_series(1, 1001) n
                        """)
                .param("user", user).param("old", catalog.now().minusHours(25)).update();
        jdbc.sql("""
                        INSERT INTO idempotency_key (user_id, endpoint, key, request_hash, created_at)
                        VALUES (:user, 'cleanup-test', 'boundary', 'hash', :boundary),
                               (:user, 'cleanup-test', 'fresh', 'hash', :now)
                        """)
                .param("user", user).param("boundary", catalog.now().minusHours(24))
                .param("now", catalog.now()).update();
        var job = new IdempotencyCleanupJob(jdbc, clock);

        job.cleanup();

        assertThat(jdbc.sql("SELECT key FROM idempotency_key WHERE user_id = :user ORDER BY key")
                .param("user", user).query(String.class).list()).containsExactly("boundary", "fresh");

        job.cleanup();

        assertThat(jdbc.sql("SELECT key FROM idempotency_key WHERE user_id = :user ORDER BY key")
                .param("user", user).query(String.class).list()).containsExactly("boundary", "fresh");
    }
}
