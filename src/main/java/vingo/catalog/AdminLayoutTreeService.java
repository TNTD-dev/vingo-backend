package vingo.catalog;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vingo.api.model.*;
import vingo.catalog.internal.SeatLayoutImportService;
import vingo.catalog.internal.VenueImportRepository;

/** Cây hiển thị theo loại và phiên bản; không xem file nhập tạm là dữ liệu sơ đồ đã lưu. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminLayoutTreeService {
    private final SeatLayoutImportService layouts;
    private final VenueImportRepository imports;

    public AdminLayoutTreeService(SeatLayoutImportService layouts, VenueImportRepository imports) {
        this.layouts = layouts;
        this.imports = imports;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLayoutTree read(UUID venue, LayoutType type, String cursor, Integer limit) {
        var page = layouts.listByVenue(venue, type.getValue(), cursor, limit);
        var versions = page.data().stream().map(header -> {
            var overview = imports.overview(header.overviewId(), false).orElseThrow();
            var zones = layouts.getOverview(header.id()).zones().stream().map(zone ->
                    new AdminLayoutTreeZone().id(zone.id()).code(zone.code()).name(zone.label())
                            .kind(AdminLayoutTreeZone.KindEnum.fromValue(zone.kind().name()))
                            .capacity(zone.capacity())).toList();
            return new AdminLayoutTreeVersion().id(header.id()).name(header.name()).version(header.version())
                    .status(AdminLayoutTreeVersion.StatusEnum.PUBLISHED)
                    .overviewId(header.overviewId()).overviewName("Nền v" + overview.getVersion())
                    .overviewStatus("PUBLISHED").publishedAt(header.createdAt()).zones(zones);
        }).toList();
        return new AdminLayoutTree().venueId(venue).layoutType(type).data(versions).nextCursor(page.nextCursor());
    }
}
