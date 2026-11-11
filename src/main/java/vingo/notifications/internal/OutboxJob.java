package vingo.notifications.internal;

import java.time.Duration;
import java.util.function.LongSupplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduling/LockProvider do shared WorkerConfig quản lý. */
@Component
@Profile("worker")
public class OutboxJob {
    private static final int MAX_MESSAGES_PER_RUN = 20;
    private static final long RUN_BUDGET_NANOS = Duration.ofSeconds(30).toNanos();
    private final OutboxDispatcher dispatcher;
    private final LongSupplier nanoTime;

    @Autowired
    public OutboxJob(OutboxDispatcher dispatcher) { this(dispatcher, System::nanoTime); }

    OutboxJob(OutboxDispatcher dispatcher, LongSupplier nanoTime) {
        this.dispatcher = dispatcher;
        this.nanoTime = nanoTime;
    }

    /**
     * Xử lý nhiều thư đến hạn; dừng nhận thư mới sau 30 giây hoặc 20 thư.
     * Đồng hồ đơn điệu tránh việc chỉnh giờ hệ thống kéo dài lô. Ngân sách này
     * không huỷ thư đang gửi và để khoảng dự phòng trước hạn ShedLock 5 phút.
     */
    @Scheduled(fixedDelayString = "${vingo.jobs.outbox-sender:PT2S}", initialDelayString = "${vingo.jobs.outbox-sender:PT2S}")
    @SchedulerLock(name = "outbox_sender", lockAtMostFor = "PT5M")
    public void sendPending() {
        long startedAt = nanoTime.getAsLong();
        for (int count = 0; count < MAX_MESSAGES_PER_RUN
                && !Thread.currentThread().isInterrupted()
                && nanoTime.getAsLong() - startedAt < RUN_BUDGET_NANOS; count++) {
            if (!dispatcher.dispatchOne()) break;
        }
    }
}
