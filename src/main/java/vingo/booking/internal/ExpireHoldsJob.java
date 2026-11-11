package vingo.booking.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vingo.booking.BookingService;

/** Profile worker chạy mỗi 5 giây với ShedLock {@code expire_holds}; mỗi batch một transaction qua BookingService, bỏ qua đơn đang bị khoá để xử lý lần sau. */
@Component
@Profile("worker")
public class ExpireHoldsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireHoldsJob.class);
    private static final int BATCH = 100;
    private static final int MAX_BATCHES = 50;

    private final BookingService booking;

    public ExpireHoldsJob(BookingService booking) {
        this.booking = booking;
    }

    // Trì hoãn lượt đầu để API có thời gian chạy migration vì worker không chạy Flyway.
    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT5S")
    @SchedulerLock(name = "expire_holds", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    public void expireHolds() {
        int total = 0;
        // Giới hạn số batch để tick kết thúc dù đơn quá hạn liên tục được thêm vào.
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int expired = booking.expireOverdueHolds(BATCH);
            total += expired;
            if (expired < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("Expired {} overdue orders", total);
        }
    }
}
