package vingo.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.api.model.LayoutType;
import vingo.api.model.OverviewArea;
import vingo.api.model.OverviewGate;
import vingo.api.model.SeatLayoutBounds;
import vingo.api.model.VenueImportCreate;
import vingo.api.model.VenueImportDraft;
import vingo.api.model.VenueImportFile;
import vingo.api.model.VenueImportIssue;
import vingo.api.model.VenueImportPage;
import vingo.api.model.VenueImportPatch;
import vingo.api.model.VenueImportPreview;
import vingo.api.model.VenueImportResult;
import vingo.api.model.VenueImportSession;
import vingo.api.model.VenueImportZonePreview;
import vingo.api.model.VenueOverview;
import vingo.api.model.VenueOverviewPage;
import vingo.catalog.internal.SeatLayoutRepository;
import vingo.catalog.internal.VenueImportRepository;
import vingo.catalog.internal.VenueImportRepository.Snapshot;
import vingo.catalog.internal.VenueImportRepository.FileSource;
import java.util.stream.Collectors;
import vingo.catalog.internal.VenueRepository.VenueData;
import vingo.catalog.internal.VenueSvgProcessor;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class VenueImportService {

    public static final int FILE_LIMIT = 10 * 1024 * 1024;
    public static final int SESSION_LIMIT = 40 * 1024 * 1024;
    public static final int FILE_COUNT = 32;

    private final VenueImportRepository repository;
    private final SeatLayoutRepository layouts;
    private final VenueSvgProcessor svg;
    private final VenueService venues;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final TransactionTemplate tx;

    public VenueImportService(
            VenueImportRepository repository,
            SeatLayoutRepository layouts,
            VenueSvgProcessor svg,
            VenueService venues,
            CurrentUser currentUser,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.repository = repository;
        this.layouts = layouts;
        this.svg = svg;
        this.venues = venues;
        this.currentUser = currentUser;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    public record Upload(String filename, byte[] bytes) {}

    private record Inspected(Upload file, VenueSvgProcessor.Source parsed, String checksum) {}

    private record ImportLayout(LayoutType type, String name, VenueSvgProcessor.Bundle bundle) {}

    @Transactional
    public VenueImportSession create(VenueImportCreate body) {
        var draft = new VenueImportDraft();
        if (body.getTargetVenueId() != null && !repository.lockVenue(body.getTargetVenueId())) {
            throw missing();
        }
        VenueOverview overview = null;
        if (body.getOverviewId() != null) {
            overview = repository.overview(body.getOverviewId(), true).orElseThrow(VenueImportService::missing);
            if (!overview.getVenueId().equals(body.getTargetVenueId())) {
                throw invalid("Nền phải thuộc sân đích.");
            }
            draft.overviewId(overview.getId());
        }
        UUID id = repository.create(owner(), body.getTargetVenueId(), draft, now());
        if (overview != null) {
            byte[] source = overview.getSvgContent().getBytes(StandardCharsets.UTF_8);
            var parsed = svg.inspect(source);
            if (!parsed.valid() || !"OVERVIEW".equals(parsed.role())) {
                throw layoutError(parsed.issues());
            }
            repository.insertFile(id, "overview.svg", parsed.role(), parsed.safeSvg());
        }
        return view(read(id, false));
    }

    @Transactional(readOnly = true)
    public VenueImportSession get(UUID id) {
        return view(read(id, false));
    }

    @Transactional(readOnly = true)
    public VenueImportPage list(String cursor, Integer limit) {
        int size = limit(limit);
        var sessions = repository.list(owner(), cursor(cursor), size + 1, now());
        boolean more = sessions.size() > size;
        var page = more ? sessions.subList(0, size) : sessions;
        var bySession = repository.files(page.stream().map(Snapshot::id).toList(), false).stream()
                .collect(Collectors.groupingBy(FileSource::sessionId));
        return new VenueImportPage()
                .data(page.stream().map(s -> view(s, bySession.getOrDefault(s.id(), List.of()))).toList())
                .nextCursor(more ? page.getLast().id().toString() : null);
    }

    @Transactional
    public VenueImportSession patch(UUID id, VenueImportPatch body) {
        var state = editable(id, body.getRevision());
        var draft = body.getDraftData() == null ? state.draft() : body.getDraftData();
        // overview_id chỉ do server gắn lúc tạo nháp, client không được đổi nó.
        if (draft.getOverviewId() != null && !draft.getOverviewId().equals(state.draft().getOverviewId())) {
            throw invalid("Không được đổi nền nguồn của phiên nhập.");
        }
        draft.overviewId(state.draft().getOverviewId());
        if (state.venueId() != null && draft.getVenue() != null) {
            throw invalid("Phiên thêm layout không sửa thông tin sân đích.");
        }
        repository.patch(id, draft, now());
        return view(read(id, false));
    }

    public VenueImportSession upload(UUID id, long revision, List<Upload> uploads) {
        var snapshot = read(id, false);
        expect(snapshot, revision);
        if (uploads == null || uploads.isEmpty()) {
            throw invalid("Cần ít nhất một file SVG.");
        }
        if (uploads.size() > FILE_COUNT) {
            throw tooLarge();
        }
        long request = 0;
        List<Inspected> parsed = new ArrayList<>();
        for (var file : uploads) {
            if (file.bytes() == null || file.bytes().length == 0) {
                throw invalid("File SVG không được rỗng.");
            }
            request += file.bytes().length;
            if (file.bytes().length > FILE_LIMIT || request > FILE_LIMIT) {
                throw tooLarge();
            }
            String filename = file.filename();
            if (filename == null || filename.isBlank() || filename.length() > 255
                    || filename.chars().anyMatch(c -> c < 32)) {
                throw invalid("Tên file không hợp lệ.");
            }
            var inspected = svg.inspect(file.bytes());
            if (!inspected.valid() || (!"OVERVIEW".equals(inspected.role()) && !"SEAT_LAYOUT".equals(inspected.role()))) {
                throw layoutError(inspected.issues().isEmpty()
                        ? List.of(Map.of("reason", "UNKNOWN_ROLE", "message", "Không nhận diện được vai trò SVG."))
                        : inspected.issues());
            }
            String checksum = checksum(inspected.safeSvg().getBytes(StandardCharsets.UTF_8));
            if (parsed.stream().noneMatch(p -> p.checksum().equals(checksum))) {
                parsed.add(new Inspected(file, inspected, checksum));
            }
        }
        return tx.execute(status -> {
            var state = editable(id, revision);
            var existing = repository.files(List.of(id), false);
            var existingChecksums = existing.stream()
                    .map(FileSource::checksum)
                    .collect(java.util.stream.Collectors.toSet());
            var fresh = parsed.stream().filter(p -> !existingChecksums.contains(p.checksum())).toList();
            long bytes = existing.stream().mapToLong(FileSource::size).sum()
                    + fresh.stream().mapToLong(p -> p.parsed().safeSvg().getBytes(StandardCharsets.UTF_8).length).sum();
            if (existing.size() + fresh.size() > FILE_COUNT || bytes > SESSION_LIMIT) {
                throw tooLarge();
            }
            long overviewCount = existing.stream().filter(f -> "OVERVIEW".equals(f.role())).count()
                    + fresh.stream().filter(p -> "OVERVIEW".equals(p.parsed().role())).count();
            if (overviewCount > 1) {
                throw invalid("Mỗi phiên chỉ có một SVG nền; xóa file cũ trước khi thay.");
            }
            for (var p : fresh) {
                repository.insertFile(id, p.file().filename(), p.parsed().role(), p.parsed().safeSvg());
            }
            if (!fresh.isEmpty()) {
                repository.patch(id, state.draft(), now());
            }
            return view(read(id, false));
        });
    }

    @Transactional
    public VenueImportSession deleteFile(UUID id, UUID file, long revision) {
        var state = editable(id, revision);
        var source = repository.files(id).stream()
                .filter(f -> f.id().equals(file))
                .findFirst()
                .orElseThrow(VenueImportService::missing);
        if (state.draft().getOverviewId() != null && "OVERVIEW".equals(source.role())) {
            throw invalid("Không thể xóa nền đã chọn; tạo phiên nhập mới để đổi nền.");
        }
        repository.deleteFile(id, file);
        repository.patch(id, state.draft(), now());
        return view(read(id, false));
    }

    public VenueImportPreview preview(UUID id, long revision) {
        var state = read(id, false);
        expect(state, revision);
        var result = new VenueImportPreview().sessionId(id).revision(revision).valid(false);
        try {
            var bundle = bundles(state).getFirst().bundle();
            result.valid(true)
                    .overviewSvg(bundle.overviewSvg())
                    .detailSvg(bundle.layout().svgContent())
                    .zoneAreas(bundle.zoneAreas())
                    .zones(bundle.layout().zones().stream().map(z -> new VenueImportZonePreview()
                            .code(z.code())
                            .label(z.label())
                            .kind(VenueImportZonePreview.KindEnum.fromValue(z.kind().name()))
                            .capacity(z.capacity())
                            .seatCount(z.seats().size())
                            .svgId(z.svgId())
                            .contentSvgId(z.contentSvgId())
                            .pitchSideSvgId(z.pitchSideSvgId())
                            .bounds(new SeatLayoutBounds(z.bounds().x(), z.bounds().y(),
                                    z.bounds().width(), z.bounds().height())))
                            .toList())
                    .gates(bundle.gates().stream().map(g -> new OverviewGate()
                            .code(g.code())
                            .name(g.code())
                            .svgId(g.svgId())
                            .direction(OverviewGate.DirectionEnum.fromValue(g.direction())))
                            .toList())
                    .areas(bundle.areas().stream().map(a -> new OverviewArea()
                            .code(a.code())
                            .name(a.code())
                            .svgId(a.svgId()))
                            .toList());
        } catch (DomainException ex) {
            if (ex.code() != ErrorCode.INVALID_SEAT_LAYOUT) {
                throw ex;
            }
            result.issues(toIssues(VenueSvgProcessor.issues(ex)));
        }
        expect(read(id, false), revision);
        return result;
    }

    public VenueImportResult complete(UUID id, long revision) {
        var state = read(id, false);
        if (state.result() != null) return state.result();
        expect(state, revision);
        metadata(state);
        var importLayouts = bundles(state);
        var bundle = importLayouts.getFirst().bundle();
        return tx.execute(status -> {
            var locked = read(id, true);
            if (locked.result() != null) return locked.result();
            expect(locked, revision);
            UUID venue = locked.venueId();
            if (venue != null && !repository.lockVenue(venue)) {
                throw missing();
            }
            metadata(locked);
            if (venue == null) {
                var v = locked.draft().getVenue();
                venue = venues.create(new VenueData(
                        v.getName(),
                        v.getAddress(),
                        v.getCity(),
                        v.getDistrict(),
                        v.getLat(),
                        v.getLng(),
                        v.getRules())).id();
            }
            UUID overview = locked.draft().getOverviewId();
            if (overview != null) {
                var base = repository.overview(overview, true).orElseThrow(VenueImportService::missing);
                if (!base.getVenueId().equals(venue) || !base.getSvgContent().equals(bundle.overviewSvg())) {
                    throw invalid("Nền nguồn đã thay đổi hoặc không thuộc sân.");
                }
            } else {
                overview = repository.insertOverview(venue, bundle.overviewSvg());
            }
            UUID layout = null;
            for (var group : importLayouts) {
                UUID created = repository.insertLayout(
                        venue, overview, group.type().getValue(), group.name(), group.bundle(), layouts);
                if (layout == null) {
                    layout = created;
                }
            }
            var result = new VenueImportResult().venueId(venue).overviewId(overview).layoutId(layout);
            repository.close(id, "COMPLETED", result, now());
            LoggerFactory.getLogger(getClass()).info(
                    "Đã hoàn tất nhập sân session_id={} venue_id={} layout_id={}", id, venue, layout);
            return result;
        });
    }

    @Transactional
    public VenueImportSession cancel(UUID id, long revision) {
        var state = read(id, true);
        if (!"CANCELLED".equals(state.status())) {
            expect(state, revision);
            repository.close(id, "CANCELLED", null, now());
            state = read(id, false);
        }
        return view(state, repository.files(List.of(id), false));
    }

    @Transactional(readOnly = true)
    public VenueOverview getOverview(UUID id) {
        return repository.overview(id, false).orElseThrow(VenueImportService::missing);
    }

    @Transactional(readOnly = true)
    public VenueOverviewPage listOverviews(UUID venue, String cursor, Integer limit) {
        if (!repository.activeVenue(venue)) {
            throw missing();
        }
        int size = limit(limit);
        var ids = repository.overviews(venue, cursor(cursor), size + 1);
        boolean more = ids.size() > size;
        var page = more ? ids.subList(0, size) : ids;
        return new VenueOverviewPage()
                .data(page.stream().map(id -> repository.overview(id, false).orElseThrow(VenueImportService::missing)).toList())
                .nextCursor(more ? page.getLast().toString() : null);
    }

    private List<ImportLayout> bundles(Snapshot state) {
        var files = repository.files(state.id());
        var bases = files.stream().filter(f -> "OVERVIEW".equals(f.role())).toList();
        if (bases.size() != 1) {
            throw layoutError(List.of(Map.of("reason", "MISSING_OVERVIEW", "message", "Cần đúng một SVG nền.")));
        }
        var base = bases.getFirst();
        var details = files.stream().filter(f -> "SEAT_LAYOUT".equals(f.role())).toList();
        var draft = state.draft();
        var types = draft.getFileLayoutTypes();
        if (types == null || types.isEmpty()) {
            String layoutName = draft.getLayoutName();
            return List.of(new ImportLayout(
                    draft.getLayoutType(),
                    layoutName == null ? null : layoutName.strip(),
                    svg.combine(base.svg(), details.stream().map(VenueImportRepository.FileSource::svg).toList(),
                            draft.getZoneAreas())));
        }
        if (details.isEmpty() || details.stream().anyMatch(f -> types.get(f.id().toString()) == null)) {
            throw layoutError(List.of(Map.of("reason", "MISSING_LAYOUT_TYPE",
                    "message", "Chọn loại layout cho từng SVG zone/ghế.")));
        }

        var result = new ArrayList<ImportLayout>();
        for (var type : LayoutType.values()) {
            var sources = details.stream()
                    .filter(f -> type == types.get(f.id().toString()))
                    .map(VenueImportRepository.FileSource::svg)
                    .toList();
            if (sources.isEmpty()) {
                continue;
            }
            var mapping = type == draft.getLayoutType() ? draft.getZoneAreas() : Map.<String, String>of();
            String name = defaultName(base.filename().substring(0, Math.min(200, base.filename().length())),
                    "Sơ đồ tổng quát");
            result.add(new ImportLayout(type, name, svg.combine(base.svg(), sources, mapping)));
        }
        result.sort(Comparator.comparing(group -> group.type() != draft.getLayoutType()));
        return result;
    }

    private void metadata(Snapshot state) {
        var d = state.draft();
        if (d.getFileLayoutTypes() == null || d.getFileLayoutTypes().isEmpty()) {
            defaultName(d.getLayoutName(), null);
        }
        if (d.getLayoutType() == null) {
            throw invalid("Cần loại layout FOOTBALL hoặc CONCERT.");
        }
        if (state.venueId() == null && (d.getVenue() == null || d.getVenue().getCity() == null
                || d.getVenue().getName() == null || d.getVenue().getName().isBlank()
                || d.getVenue().getAddress() == null || d.getVenue().getAddress().isBlank())) {
            throw invalid("Cần tên, địa chỉ và thành phố của sân.");
        }
    }

    private VenueImportSession view(Snapshot s) {
        return view(s, repository.files(s.id()));
    }

    private VenueImportSession view(Snapshot s, List<FileSource> sources) {
        var files = sources.stream()
                .map(f -> new VenueImportFile()
                            .id(f.id())
                            .originalFilename(f.filename())
                            .role(VenueImportFile.RoleEnum.fromValue(f.role()))
                            .validationStatus(VenueImportFile.ValidationStatusEnum.VALIDATED)
                            .sizeBytes(f.size())
                            .checksum(f.checksum())
                            .schemaVersion(2)
                            .sanitizedSvg(f.svg())
                            .issues(List.of()))
                .toList();
        return new VenueImportSession()
                .id(s.id())
                .targetVenueId(s.venueId())
                .status(VenueImportSession.StatusEnum.fromValue(s.status()))
                .step(VenueImportSession.StepEnum.OVERVIEW)
                .revision(s.revision())
                .expiresAt(s.expiresAt())
                .draftData(s.draft())
                .files(files);
    }

    private List<VenueImportIssue> toIssues(List<Map<String, Object>> issues) {
        return issues.stream()
                .map(i -> new VenueImportIssue()
                        .reason(String.valueOf(i.getOrDefault("reason", "INVALID_SEAT_LAYOUT")))
                        .message(String.valueOf(i.getOrDefault("message", "Sơ đồ chưa hợp lệ."))))
                .toList();
    }

    private Snapshot read(UUID id, boolean lock) {
        var s = repository.find(id, owner(), lock).orElseThrow(VenueImportService::missing);
        if (!s.expiresAt().isAfter(now())) {
            throw missing();
        }
        return s;
    }

    private Snapshot editable(UUID id, long revision) {
        var s = read(id, true);
        expect(s, revision);
        return s;
    }

    private void expect(Snapshot state, long revision) {
        if (!"DRAFT".equals(state.status()) || state.revision() != revision) {
            throw new DomainException(ErrorCode.ACTION_STATE_INVALID,
                    "Phiên nhập đã thay đổi hoặc đã đóng.",
                    Map.of("current_revision", state.revision()));
        }
    }

    private UUID owner() {
        return currentUser.require().id();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String defaultName(String name, String fallback) {
        if (name == null && fallback != null) {
            return fallback;
        }
        if (name == null || name.isBlank() || name.length() > 200) {
            throw invalid("Tên bắt buộc và tối đa 200 ký tự.");
        }
        return name.strip();
    }

    private static int limit(Integer n) {
        int size = n == null ? 20 : n;
        if (size < 1 || size > 100) {
            throw invalid("limit phải từ 1 đến 100.");
        }
        return size;
    }

    private static UUID cursor(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalid("Cursor không hợp lệ.");
        }
    }

    private static String checksum(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static DomainException missing() {
        return new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sân, nền hoặc phiên nhập còn hiệu lực.");
    }

    private static DomainException invalid(String m) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, m);
    }

    private static DomainException tooLarge() {
        return new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, "Tối đa 10 MiB/file/request và 32 file, 40 MiB/phiên.");
    }

    private static DomainException layoutError(List<Map<String, Object>> issues) {
        return new DomainException(ErrorCode.INVALID_SEAT_LAYOUT, "Sơ đồ chưa hợp lệ.", Map.of("issues", issues));
    }
}
