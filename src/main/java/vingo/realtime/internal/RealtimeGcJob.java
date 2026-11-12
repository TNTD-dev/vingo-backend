package vingo.realtime.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dọn sự kiện quá hạn lưu theo lô trên worker, khoá chung bằng ShedLock. */
@Component
@Profile("worker")
public class RealtimeGcJob {

    private static final Logger log = LoggerFactory.getLogger(RealtimeGcJob.class);
    private final JdbcClient jdbc;
    private final Clock clock;
    private final RealtimeProperties properties;
    private final int batchSize;

    /** Dùng lô 5000 cho lịch chạy worker mỗi phút. */
    @Autowired
    public RealtimeGcJob(JdbcClient jdbc, Clock clock, RealtimeProperties properties) {
        this(jdbc, clock, properties, 5000);
    }

    /** Cho phép test dùng lô nhỏ mà không cần khởi động profile worker. */
    public RealtimeGcJob(JdbcClient jdbc, Clock clock, RealtimeProperties properties, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize phải lớn hơn 0");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.batchSize = batchSize;
    }

    /** Xoá hết các lô cũ hơn retention; chạy lại không ảnh hưởng sự kiện còn hạn. */
    @Scheduled(fixedDelayString = "${vingo.jobs.realtime-gc}", initialDelayString = "PT1M")
    @SchedulerLock(name = "realtime_gc", lockAtMostFor = "PT1M")
    public void cleanup() {
        var cutoff = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(properties.retention());
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM realtime_event WHERE id IN (
                                SELECT id FROM realtime_event WHERE created_at < :cutoff
                                ORDER BY created_at, id LIMIT :batch
                            )
                            """)
                    .param("cutoff", cutoff).param("batch", batchSize).update();
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("Realtime GC deleted {} events", total);
        }
    }
}
