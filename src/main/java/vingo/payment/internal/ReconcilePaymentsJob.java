package vingo.payment.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import vingo.payment.PaymentService;

/**
 * Job nền profile worker đối soát các payment_intent OPEN với VNPay mỗi phút.
 */
@Component
@Profile("worker")
public class ReconcilePaymentsJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcilePaymentsJob.class);
    private static final int BATCH = 50;
    private static final int MAX_BATCHES = 10;
    private java.time.OffsetDateTime cursorTime;
    private java.util.UUID cursorId;

    private final PaymentService paymentService;

    public ReconcilePaymentsJob(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${vingo.jobs.reconcile-payments}", initialDelayString = "PT10S")
    @SchedulerLock(name = "reconcile_payments", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void reconcilePayments() {
        try {
            int total = 0;
            java.time.OffsetDateTime afterTime = cursorTime;
            java.util.UUID afterId = cursorId;
            for (int i = 0; i < MAX_BATCHES; i++) {
                var batch = paymentService.reconcileBatch(BATCH, afterTime, afterId);
                total += batch.processed();
                afterTime = batch.createdAt();
                afterId = batch.id();
                boolean endReached = batch.processed() < BATCH; 
                cursorTime = endReached ? null : afterTime;
                cursorId = endReached ? null : afterId;
                if (endReached) {
                    break;
                }
            }
            if (total > 0) {
                log.info("Reconciled {} payment intents", total);
            }
        } catch (RuntimeException ex) {
            log.error("Unexpected error in reconcile_payments job", ex);
        }
    }
}
