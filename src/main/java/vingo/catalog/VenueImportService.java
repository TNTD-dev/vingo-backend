package vingo.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.*;
import vingo.catalog.internal.VenueImportRepository;
import vingo.catalog.internal.VenueImportRepository.Snapshot;
import vingo.catalog.internal.VenueSvgProcessor;
import vingo.catalog.internal.VenueRepository.VenueData;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class VenueImportService {
    public static final int FILE_LIMIT=10*1024*1024, SESSION_LIMIT=40*1024*1024, FILE_COUNT=32;
    private final VenueImportRepository repository;
    private final VenueSvgProcessor svg;
    private final VenueService venues;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    public VenueImportService(VenueImportRepository repository,VenueSvgProcessor svg,VenueService venues,
                              CurrentUser currentUser,Clock clock,JsonMapper json,PlatformTransactionManager transactions) {
        this.repository=repository;this.svg=svg;this.venues=venues;this.currentUser=currentUser;this.clock=clock;this.json=json;
        this.tx=new TransactionTemplate(transactions);
    }
    public record Upload(String filename,byte[] bytes) {}
    private record Inspected(Upload file,VenueSvgProcessor.Source parsed,String checksum) {}
    @Transactional
    public VenueImportSession create(VenueImportCreate body) {
        var draft=new VenueImportDraft();
        if(body.getTargetVenueId()!=null&&!repository.lockVenue(body.getTargetVenueId())) throw missing();
        VenueOverview overview=null;
        if(body.getOverviewId()!=null) {
            overview=repository.overview(body.getOverviewId(),true).orElseThrow(VenueImportService::missing);
            if(!overview.getVenueId().equals(body.getTargetVenueId())||overview.getStatus()!=VenueOverview.StatusEnum.PUBLISHED)
                throw invalid("Nền phải đã phát hành và thuộc sân đích.");
            draft.overviewId(overview.getId());
        }
        UUID id=repository.create(owner(),body.getTargetVenueId(),draft,now());
        if(overview!=null) {
            byte[] source=overview.getSvgContent().getBytes(StandardCharsets.UTF_8);
            var parsed=svg.inspect(source);
            if(!parsed.valid()||!parsed.role().equals("OVERVIEW")) throw layoutError(parsed.issues());
            repository.insertFile(id,"overview.svg",source,parsed);
        }
        return view(read(id,false));
    }
    @Transactional(readOnly=true)
    public VenueImportSession get(UUID id) {return view(read(id,false));}
    @Transactional(readOnly=true)
    public VenueImportPage list(String cursor,Integer limit) {
        int size=limit(limit);var ids=repository.list(owner(),cursor(cursor),size+1,now());
        boolean more=ids.size()>size;var page=more?ids.subList(0,size):ids;
        return new VenueImportPage().data(page.stream().map(id->view(read(id,false),false)).toList())
                .nextCursor(more?page.getLast().toString():null);
    }
    @Transactional
    public VenueImportSession patch(UUID id,VenueImportPatch body) {
        var state=editable(id,body.getRevision());
        var draft=body.getDraftData()==null?state.draft():body.getDraftData();
        // overview_id chỉ do server gắn lúc tạo nháp, client không được đổi nó.
        if(draft.getOverviewId()!=null&&!draft.getOverviewId().equals(state.draft().getOverviewId())) throw invalid("Không được đổi nền nguồn của phiên nhập.");
        draft.overviewId(state.draft().getOverviewId());
        if(state.venueId()!=null&&draft.getVenue()!=null) throw invalid("Phiên thêm layout không sửa thông tin sân đích.");
        var origin=VenueSetupService.location(repository,state,draft);
        VenueSetupService.protectMetadata(state.draft(),draft,origin);
        VenueSetupService.validateDraft(draft,origin);
        repository.patch(id,body.getStep()==null?state.step():body.getStep().getValue(),draft,now());
        return view(read(id,false));
    }
    public VenueImportSession upload(UUID id,long revision,List<Upload> uploads) {
        var snapshot=read(id,false);expect(snapshot,revision);
        if(uploads==null||uploads.isEmpty()) throw invalid("Cần ít nhất một file SVG.");
        if(uploads.size()>FILE_COUNT) throw tooLarge();
        long request=0;List<Inspected> parsed=new ArrayList<>();
        for(var file:uploads) {
            if(file.bytes()==null||file.bytes().length==0) throw invalid("File SVG không được rỗng.");
            request+=file.bytes().length;
            if(file.bytes().length>FILE_LIMIT||request>FILE_LIMIT) throw tooLarge();
            String filename=file.filename();
            if(filename==null||filename.isBlank()||filename.length()>255||filename.chars().anyMatch(c->c<32)) throw invalid("Tên file không hợp lệ.");
            String checksum=checksum(file.bytes());
            if(parsed.stream().noneMatch(p->p.checksum().equals(checksum))) parsed.add(new Inspected(file,svg.inspect(file.bytes()),checksum));
        }
        return tx.execute(status->{
            var state=editable(id,revision);var existing=repository.files(id);
            var fresh=parsed.stream().filter(p->existing.stream().noneMatch(f->f.checksum().equals(p.checksum()))).toList();
            long bytes=existing.stream().mapToLong(f->f.size()).sum()+fresh.stream().mapToLong(p->p.file().bytes().length).sum();
            if(existing.size()+fresh.size()>FILE_COUNT||bytes>SESSION_LIMIT) throw tooLarge();
            long overviewCount=existing.stream().filter(f->f.role().equals("OVERVIEW")).count()+fresh.stream().filter(p->p.parsed().role().equals("OVERVIEW")).count();
            if(overviewCount>1) throw invalid("Mỗi phiên chỉ có một SVG nền; xóa file cũ trước khi thay.");
            for(var p:fresh) repository.insertFile(id,p.file().filename(),p.file().bytes(),p.parsed());
            if(!fresh.isEmpty()) repository.patch(id,state.step(),state.draft(),now());
            return view(read(id,false));
        });
    }
    @Transactional
    public VenueImportSession deleteFile(UUID id,UUID file,long revision) {
        var state=editable(id,revision);
        var source=repository.files(id).stream().filter(f->f.id().equals(file)).findFirst().orElseThrow(VenueImportService::missing);
        if(state.draft().getOverviewId()!=null&&source.role().equals("OVERVIEW")) throw invalid("Không thể xóa nền đã chọn; tạo phiên nhập mới để đổi nền.");
        repository.deleteFile(id,file);repository.patch(id,state.step(),state.draft(),now());return view(read(id,false));
    }
    public VenueImportPreview preview(UUID id,long revision) {
        var state=read(id,false);expect(state,revision);
        var result=new VenueImportPreview().sessionId(id).revision(revision).valid(false);
        try {
            var bundle=bundles(state).getFirst().bundle();
            result.valid(true).overviewSvg(bundle.overviewSvg()).detailSvg(bundle.layout().svgContent()).zoneAreas(bundle.zoneAreas())
                    .zones(bundle.layout().zones().stream().map(z->new VenueImportZonePreview().code(z.code()).label(z.label())
                            .kind(VenueImportZonePreview.KindEnum.fromValue(z.kind().name())).capacity(z.capacity()).seatCount(z.seats().size()).svgId(z.svgId())
                            .contentSvgId(z.contentSvgId()).pitchSideSvgId(z.pitchSideSvgId())
                            .bounds(new SeatLayoutBounds(z.bounds().x(),z.bounds().y(),z.bounds().width(),z.bounds().height()))).toList())
                    .gates(bundle.gates().stream().map(g->new OverviewGate().code(g.code()).name(g.code()).svgId(g.svgId()).direction(OverviewGate.DirectionEnum.fromValue(g.direction()))).toList())
                    .areas(bundle.areas().stream().map(a->new OverviewArea().code(a.code()).name(a.code()).svgId(a.svgId())).toList());
        } catch(DomainException ex) {
            if(ex.code()!=ErrorCode.INVALID_SEAT_LAYOUT) throw ex;
            result.issues(toIssues(VenueSvgProcessor.issues(ex)));
        }
        expect(read(id,false),revision);return result;
    }
    public VenueImportResult complete(UUID id,long revision) {
        var state=read(id,false);
        if(state.status().equals("COMPLETED")) return result(state);
        expect(state,revision);metadata(state);
        var layouts=bundles(state);
        var bundle=layouts.getFirst().bundle();
        return tx.execute(status->{
            var locked=read(id,true);
            if(locked.status().equals("COMPLETED")) return result(locked);
            expect(locked,revision);
            UUID venue=locked.venueId();
            if(venue!=null&&!repository.lockVenue(venue)) throw missing();
            metadata(locked);
            if(venue==null) {
                var v=locked.draft().getVenue();
                venue=venues.create(new VenueData(v.getName(),"STADIUM",v.getAddress(),v.getCity()==null?null:v.getCity().getValue(),v.getDistrict(),v.getLat(),v.getLng(),
                        v.getInfo()==null?"{}":json.writeValueAsString(v.getInfo()))).id();
            }
            repository.persistSetup(id,venue,locked.draft());
            UUID overview=locked.draft().getOverviewId();
            if(overview!=null) {
                var base=repository.overview(overview,true).orElseThrow(VenueImportService::missing);
                if(!base.getVenueId().equals(venue)||base.getStatus()!=VenueOverview.StatusEnum.PUBLISHED||!base.getSvgContent().equals(bundle.overviewSvg()))
                    throw invalid("Nền nguồn đã thay đổi hoặc không thuộc sân.");
            } else overview=repository.insertOverview(venue,defaultName(locked.draft().getOverviewName(),"Sơ đồ tổng quát"),bundle);
            UUID layout=null;
            for (var group : layouts) {
                UUID created=repository.insertLayout(venue,overview,group.type().getValue(),group.name(),group.bundle());
                repository.certify(overview,created,now());
                if (layout==null) layout=created;
            }
            repository.close(id,"COMPLETED",venue,layout,now());
            LoggerFactory.getLogger(getClass()).info("Đã hoàn tất nhập sân session_id={} venue_id={} layout_id={}",id,venue,layout);
            return new VenueImportResult().venueId(venue).overviewId(overview).layoutId(layout);
        });
    }
    @Transactional
    public VenueImportSession cancel(UUID id,long revision) {
        var state=read(id,true);if(state.status().equals("CANCELLED")) return view(state);
        expect(state,revision);repository.close(id,"CANCELLED",null,null,now());return view(read(id,false));
    }
    @Transactional(readOnly=true)
    public VenueOverview getOverview(UUID id) {return repository.overview(id,false).orElseThrow(VenueImportService::missing);}
    @Transactional(readOnly=true)
    public VenueOverviewPage listOverviews(UUID venue,String cursor,Integer limit) {
        if(!repository.activeVenue(venue)) throw missing();
        int size=limit(limit);var ids=repository.overviews(venue,cursor(cursor),size+1);boolean more=ids.size()>size;
        var page=more?ids.subList(0,size):ids;
        return new VenueOverviewPage().data(page.stream().map(id->repository.overview(id,false).orElseThrow(VenueImportService::missing)).toList())
                .nextCursor(more?page.getLast().toString():null);
    }
    private record ImportLayout(LayoutType type, String name, VenueSvgProcessor.Bundle bundle) {}

    private List<ImportLayout> bundles(Snapshot state) {
        var files=repository.files(state.id());
        List<Map<String,Object>> issues=new ArrayList<>();
        for(var f:files) if(!f.status().equals("VALIDATED")) {
            var fileIssues=readIssues(f.issues());
            if(fileIssues.isEmpty()) issues.add(Map.of("reason","FILE_NOT_VALIDATED","message","File chưa được kiểm tra; hãy tải lại file.","file_id",f.id().toString()));
            for(var issue:fileIssues) {
                @SuppressWarnings("unchecked") var copy=new java.util.LinkedHashMap<String,Object>((Map<String,Object>)issue);
                copy.put("file_id",f.id().toString());issues.add(copy);
            }
        }
        if(!issues.isEmpty()) throw layoutError(issues);
        var bases=files.stream().filter(f->f.role().equals("OVERVIEW")).toList();
        if(bases.size()!=1) throw layoutError(List.of(Map.of("reason","MISSING_OVERVIEW","message","Cần đúng một SVG nền.")));
        var base=bases.getFirst();
        var details=files.stream().filter(f->f.role().equals("SEAT_LAYOUT")).toList();
        var draft=state.draft();
        var types=draft.getFileLayoutTypes();
        if(types==null||types.isEmpty()) {
            String layoutName = draft.getLayoutName();
            return List.of(new ImportLayout(draft.getLayoutType(), layoutName == null ? null : layoutName.strip(),
                    svg.combine(base.safeSvg(),details.stream().map(f->f.safeSvg()).toList(),draft.getZoneAreas())));
        }
        if(details.isEmpty() || details.stream().anyMatch(f->types.get(f.id().toString())==null))
            throw layoutError(List.of(Map.of("reason","MISSING_LAYOUT_TYPE","message","Chọn loại layout cho từng SVG zone/ghế.")));

        var layouts=new ArrayList<ImportLayout>();
        for(var type:LayoutType.values()) {
            var sources=details.stream().filter(f->type==types.get(f.id().toString())).map(f->f.safeSvg()).toList();
            if(sources.isEmpty()) continue;
            var mapping=type==draft.getLayoutType()?draft.getZoneAreas():Map.<String,String>of();
            String name=defaultName(base.filename().substring(0,Math.min(200,base.filename().length())),"Sơ đồ tổng quát");
            layouts.add(new ImportLayout(type,name,svg.combine(base.safeSvg(),sources,mapping)));
        }
        layouts.sort(java.util.Comparator.comparing(group->group.type()!=draft.getLayoutType()));
        return layouts;
    }

    private void metadata(Snapshot state) {
        var d=state.draft();
        VenueSetupService.validateDraft(d,VenueSetupService.location(repository,state,d));
        if(d.getFileLayoutTypes()==null||d.getFileLayoutTypes().isEmpty())defaultName(d.getLayoutName(),null);
        if(d.getLayoutType()==null) throw invalid("Cần loại layout FOOTBALL hoặc CONCERT.");
        defaultName(d.getOverviewName(),"Sơ đồ tổng quát");
        if(state.venueId()==null&&(d.getVenue()==null||(d.getVenue().getCity()==null && d.getVenue().getLocation()==null)||d.getVenue().getName()==null||d.getVenue().getName().isBlank()
                ||d.getVenue().getAddress()==null||d.getVenue().getAddress().isBlank())) throw invalid("Cần tên, địa chỉ và thành phố của sân.");
        if(d.getVenue()!=null&&d.getVenue().getType()!=null&&!d.getVenue().getType().getValue().equals("STADIUM")) throw invalid("Chỉ hỗ trợ sân vận động.");
    }
    private VenueImportSession view(Snapshot s) {return view(s,true);}
    private VenueImportSession view(Snapshot s,boolean includeSvg) {
        var files=repository.files(s.id()).stream().map(f->new VenueImportFile().id(f.id()).originalFilename(f.filename())
                .role(VenueImportFile.RoleEnum.fromValue(f.role())).validationStatus(VenueImportFile.ValidationStatusEnum.fromValue(f.status()))
                .sizeBytes(f.size()).checksum(f.checksum()).schemaVersion(2).sanitizedSvg(includeSvg?f.safeSvg():null)
                .issues(toIssues(readIssues(f.issues())))).toList();
        return new VenueImportSession().id(s.id()).targetVenueId(s.venueId()).status(VenueImportSession.StatusEnum.fromValue(s.status()))
                .step(VenueImportSession.StepEnum.fromValue(s.step())).revision(s.revision()).expiresAt(s.expiresAt()).draftData(s.draft()).files(files)
                .result(s.status().equals("COMPLETED")?result(s):null);
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> readIssues(String value) {return json.readValue(value,List.class);}
    private List<VenueImportIssue> toIssues(List<Map<String,Object>> issues) {return issues.stream().map(i->json.convertValue(i,VenueImportIssue.class)).toList();}
    private Snapshot read(UUID id,boolean lock) {var s=repository.find(id,owner(),lock).orElseThrow(VenueImportService::missing);if(!s.expiresAt().isAfter(now())) throw missing();return s;}
    private Snapshot editable(UUID id,long revision) {var s=read(id,true);expect(s,revision);return s;}
    private void expect(Snapshot state,long revision) {if(!state.status().equals("DRAFT")||state.revision()!=revision) throw new DomainException(ErrorCode.ACTION_STATE_INVALID,"Phiên nhập đã thay đổi hoặc đã đóng.",Map.of("current_revision",state.revision()));}
    private VenueImportResult result(Snapshot s) {return new VenueImportResult().venueId(s.completedVenue()).layoutId(s.completedLayout()).overviewId(repository.overviewIdForLayout(s.completedLayout()));}
    private UUID owner() {return currentUser.require().id();}
    private OffsetDateTime now() {return OffsetDateTime.now(clock);}
    private static String defaultName(String name,String fallback) {if(name==null&&fallback!=null)return fallback;if(name==null||name.isBlank()||name.length()>200)throw invalid("Tên bắt buộc và tối đa 200 ký tự.");return name.strip();}
    private static int limit(Integer n) {int size=n==null?20:n;if(size<1||size>100)throw invalid("limit phải từ 1 đến 100.");return size;}
    private static UUID cursor(String value) {try{return value==null?null:UUID.fromString(value);}catch(IllegalArgumentException ex){throw invalid("Cursor không hợp lệ.");}}
    private static String checksum(byte[] b) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static DomainException missing() {return new DomainException(ErrorCode.NOT_FOUND,"Không tìm thấy sân, nền hoặc phiên nhập còn hiệu lực.");}
    private static DomainException invalid(String m) {return new DomainException(ErrorCode.VALIDATION_ERROR,m);}
    private static DomainException tooLarge() {return new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,"Tối đa 10 MiB/file/request và 32 file, 40 MiB/phiên.");}
    private static DomainException layoutError(List<Map<String,Object>> issues) {return new DomainException(ErrorCode.INVALID_SEAT_LAYOUT,"Sơ đồ chưa hợp lệ.",Map.of("issues",issues));}
}
