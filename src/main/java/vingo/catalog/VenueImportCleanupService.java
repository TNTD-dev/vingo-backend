package vingo.catalog;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vingo.catalog.internal.VenueImportRepository;

@Service
public class VenueImportCleanupService {
    private final VenueImportRepository repository;
    private final Clock clock;
    public VenueImportCleanupService(VenueImportRepository repository,Clock clock) {this.repository=repository;this.clock=clock;}
    @Transactional
    public int cleanupBatch() {return repository.cleanup(OffsetDateTime.now(clock),100);}
}
