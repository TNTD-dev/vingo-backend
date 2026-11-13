package vingo.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vingo.realtime.internal.RealtimeGcJob;
import vingo.realtime.internal.RealtimeProperties;
import vingo.support.IntegrationTest;

class RealtimeGcJobTest extends IntegrationTest {

    private final RealtimeProperties properties;
    private UUID user;
    private RealtimeGcJob job;

    @Autowired
    RealtimeGcJobTest(RealtimeProperties properties) {
        this.properties = properties;
    }

    @BeforeEach
    void createJob() {
        clock.set(clock.instant());
        user = catalog.buyer();
        job = new RealtimeGcJob(jdbc, clock, properties, 2);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void cleanup_deletesElevenMinuteOldEventsButKeepsNineMinutesAndExactBoundary() {
        UUID old = insertEvent(Duration.ofMinutes(11));
        UUID boundary = insertEvent(Duration.ofMinutes(10));
        UUID recent = insertEvent(Duration.ofMinutes(9));

        assertTimeoutPreemptively(Duration.ofSeconds(5), job::cleanup);

        assertThat(remaining()).containsExactlyInAnyOrder(boundary, recent).doesNotContain(old);
    }

    @Test
    void cleanup_canRunAgainWithoutChangingRetainedEvents() {
        insertEvent(Duration.ofMinutes(11));
        UUID recent = insertEvent(Duration.ofMinutes(9));
        assertTimeoutPreemptively(Duration.ofSeconds(5), job::cleanup);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertDoesNotThrow(job::cleanup));

        assertThat(remaining()).containsExactly(recent);
    }

    @Test
    void cleanup_drainsMoreThanOneBatch() {
        List<UUID> expired = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expired.add(insertEvent(Duration.ofMinutes(11)));
        }
        UUID recent = insertEvent(Duration.ofMinutes(9));

        assertTimeoutPreemptively(Duration.ofSeconds(5), job::cleanup);

        assertThat(remaining()).containsExactly(recent).doesNotContainAnyElementsOf(expired);
    }

    private UUID insertEvent(Duration age) {
        return jdbc.sql("""
                        INSERT INTO realtime_event (user_id, type, payload, created_at)
                        VALUES (:user, 'order.held', '{}'::jsonb, :createdAt) RETURNING id
                        """)
                .param("user", user).param("createdAt", OffsetDateTime.now(clock).minus(age))
                .query(UUID.class).single();
    }

    private List<UUID> remaining() {
        return jdbc.sql("SELECT id FROM realtime_event WHERE user_id = :user ORDER BY id")
                .param("user", user).query(UUID.class).list();
    }
}
