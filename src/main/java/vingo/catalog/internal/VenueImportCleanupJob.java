package vingo.catalog.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vingo.catalog.VenueImportCleanupService;

@Component
@Profile("worker")
public class VenueImportCleanupJob {
    private final VenueImportCleanupService service;
    public VenueImportCleanupJob(VenueImportCleanupService service) {this.service=service;}
    @Scheduled(fixedDelayString="PT5M",initialDelayString="PT1M")
    @SchedulerLock(name="venue_import_cleanup",lockAtMostFor="PT4M")
    public void cleanup() {
        // Giới hạn mỗi lượt; phiên đóng được giữ 7 ngày để client có thể retry complete.
        for(int batch=0;batch<20;batch++) if(service.cleanupBatch()<100) break;
    }
}
