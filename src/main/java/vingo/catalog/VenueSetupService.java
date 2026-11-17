package vingo.catalog;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.*;
import vingo.catalog.internal.VenueImportRepository;
import vingo.catalog.internal.VenueSetupClients;
import vingo.shared.error.*;
import vingo.shared.ratelimit.RateLimiter;
import vingo.shared.security.CurrentUser;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class VenueSetupService {
    private final VenueImportRepository imports;
    private final VenueImportService sessions;
    private final VenueSetupClients clients;
    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final RateLimiter rates;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    public VenueSetupService(VenueImportRepository imports,VenueImportService sessions,VenueSetupClients clients,
        JdbcClient jdbc,CurrentUser user,RateLimiter rates,JsonMapper json,Clock clock,PlatformTransactionManager transactions){
        this.imports=imports;this.sessions=sessions;this.clients=clients;this.jdbc=jdbc;this.user=user;this.rates=rates;this.json=json;this.clock=clock;
        tx=new TransactionTemplate(transactions);
    }
    public List<LocationSuggestion> suggest(String q,int limit){rates.check(user.require().id(),"geo-suggest",60,Duration.ofMinutes(1));return clients.suggest(q,limit);}
    public List<LocationSuggestion> nearbyParking(java.math.BigDecimal lat, java.math.BigDecimal lng, int limit) {
        rates.check(user.require().id(), "geo-parking", 30, Duration.ofMinutes(1));
        return clients.nearbyParking(lat, lng, limit);
    }

    public LocationSuggestion reverse(java.math.BigDecimal lat,java.math.BigDecimal lng){rates.check(user.require().id(),"geo-reverse",60,Duration.ofMinutes(1));return clients.reverse(lat,lng);}
    public VenueImportSession generate(UUID id,ImportParkingGuidanceRequest body){
        var state=editable(id,body.getRevision());
        var draft=state.draft();
        var origin=location(imports,state,draft);
        validateDraft(draft,origin);
        var list=draft.getParkingLocations();
        int index=body.getParkingIndex();if(list==null||index>=list.size())throw invalid("Chọn bãi xe trước khi sinh hướng dẫn.");
        if(origin==null)throw invalid("Chọn vị trí sân trước khi sinh hướng dẫn.");
        rates.check(user.require().id(),"parking-guidance",5,Duration.ofMinutes(1));
        var parking=list.get(index);clients.route(origin,parking);
        String text=clients.guidance(origin,parking);
        parking.guidanceText(text).guidanceApproved(false).guidanceInputHash(fingerprint(origin,parking))
            .guidanceModel(clients.model()).guidanceGeneratedAt(now());
        return tx.execute(status->{
            var locked=imports.find(id,user.require().id(),true).orElseThrow(VenueSetupService::missing);
            if(locked.revision()!=body.getRevision()||!locked.status().equals("DRAFT")||locked.expiresAt().isBefore(now()))throw conflict();
            imports.patch(id,state.step(),draft,now());
            return sessions.get(id);
        });
    }
    static VenueLocationInput location(VenueImportRepository imports, VenueImportRepository.Snapshot state,
                                       VenueImportDraft draft) {
        if (state.venueId() == null) {
            return draft.getVenue() == null ? null : draft.getVenue().getLocation();
        }
        return imports.venueLocation(state.venueId()).orElseThrow(VenueSetupService::missing);
    }
    private VenueImportRepository.Snapshot editable(UUID id,long revision){
        var s=imports.find(id,user.require().id(),false).orElseThrow(VenueSetupService::missing);
        if(!s.status().equals("DRAFT")||s.revision()!=revision||s.expiresAt().isBefore(now()))throw conflict();
        return s;
    }
    public VenueRulesDocument upload(UUID session,long revision,String filename,String mime,byte[] bytes){
        editable(session,revision);
        if(bytes.length>20*1024*1024)throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,"PDF tối đa 20 MiB.");
        if(!"application/pdf".equals(mime)||bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new DomainException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,"Chọn file PDF hợp lệ.");
        if(filename==null||filename.length()>255||filename.chars().anyMatch(c->c<32))throw invalid("Tên file PDF không hợp lệ.");
        UUID document=UUID.randomUUID();
        var processed=clients.upload(document,bytes);
        var model=json.treeToValue(processed,VenueRulesDocument.class).id(document).filename(filename).sizeBytes(bytes.length)
            .sha256(sha(bytes)).uploadedAt(now()).uploadedBy(user.require().id());
        try {
            tx.executeWithoutResult(status->{
                // Lock the session so complete/upload cannot race.
                var locked=imports.find(session,user.require().id(),true).orElseThrow(VenueSetupService::missing);
                if(locked.revision()!=revision||!locked.status().equals("DRAFT"))throw conflict();
                jdbc.sql("UPDATE venue_rules_documents SET deleted_at=:now WHERE session_id=:session AND deleted_at IS NULL")
                    .param("now",now()).param("session",session).update();
                jdbc.sql("INSERT INTO venue_rules_documents(id,session_id,uploaded_by,metadata,uploaded_at) VALUES(:id,:session,:owner,CAST(:data AS jsonb),:now)")
                    .param("id",document).param("session",session).param("owner",user.require().id()).param("data",json.writeValueAsString(model)).param("now",now()).update();
            });
        }catch(RuntimeException ex){clients.delete(document);throw ex;}
        return model;
    }
    public VenueRulesDocument document(UUID session){
        sessions.get(session); // owner check, including completed session
        var model=jdbc.sql("SELECT metadata::text FROM venue_rules_documents WHERE session_id=:id AND deleted_at IS NULL")
            .param("id",session).query(String.class).optional().map(s->json.readValue(s,VenueRulesDocument.class)).orElseThrow(VenueSetupService::missing);
        if(model.getExtractionStatus()==RulesExtractionStatus.QUEUED||model.getExtractionStatus()==RulesExtractionStatus.PROCESSING){
            var result=clients.document(model.getId());
            model.extractionStatus(RulesExtractionStatus.fromValue(result.path("extraction_status").asText()))
                .extractedText(result.path("extracted_text").asText(null)).extractedTextTruncated(result.path("extracted_text_truncated").asBoolean());
            if(result.has("error_code"))model.errorCode(VenueRulesDocument.ErrorCodeEnum.fromValue(result.path("error_code").asText()));
            if(model.getExtractionStatus()!=RulesExtractionStatus.PROCESSING&&model.getExtractionStatus()!=RulesExtractionStatus.QUEUED)model.extractedAt(now());
            jdbc.sql("UPDATE venue_rules_documents SET metadata=CAST(:data AS jsonb) WHERE id=:id")
                .param("data",json.writeValueAsString(model)).param("id",model.getId()).update();
        }
        return model;
    }
    public byte[] download(UUID session){var d=document(session);return clients.download(d.getId());}
    static void protectMetadata(VenueImportDraft previous,VenueImportDraft draft,VenueLocationInput origin){
        if(draft.getParkingLocations()==null)return;
        var old=previous.getParkingLocations()==null?List.<ParkingSetupDraft>of():previous.getParkingLocations();
        for(var p:draft.getParkingLocations()){
            if(origin==null||p.getLocation()==null)continue;
            String hash=fingerprint(origin,p);
            var source=old.stream().filter(o->hash.equals(o.getGuidanceInputHash())).findFirst();
            if(source.isPresent()){
                var o=source.get();p.guidanceInputHash(o.getGuidanceInputHash()).guidanceModel(o.getGuidanceModel())
                    .guidanceGeneratedAt(o.getGuidanceGeneratedAt()).straightLineMeters(o.getStraightLineMeters())
                    .routeDistanceMeters(o.getRouteDistanceMeters()).routeDurationSeconds(o.getRouteDurationSeconds());
            }else{
                boolean stale = (p.getGuidanceInputHash() != null && !hash.equals(p.getGuidanceInputHash()))
                        || (p.getGuidanceText() != null && old.stream().anyMatch(o -> o.getGuidanceInputHash() != null
                            && !hash.equals(o.getGuidanceInputHash()) && p.getGuidanceText().equals(o.getGuidanceText())));
                if (stale && Boolean.TRUE.equals(p.getGuidanceApproved())) p.guidanceApproved(false);
                p.guidanceInputHash(null).guidanceModel(null).guidanceGeneratedAt(null).straightLineMeters(null)
                    .routeDistanceMeters(null).routeDurationSeconds(null);
                if(p.getGuidanceText()!=null&&!p.getGuidanceText().isBlank())p.guidanceModel("MANUAL").guidanceInputHash(hash);
            }
        }
    }
    static void validateDraft(VenueImportDraft draft,VenueLocationInput origin){
        if(draft.getVenue()!=null&&draft.getVenue().getLocation()!=null){
            var l=draft.getVenue().getLocation();VenueSetupClients.coordinates(l.getLatitude(),l.getLongitude());
            if(l.getDisplayAddress()==null||l.getDisplayAddress().isBlank()||l.getDisplayAddress().length()>500)throw invalid("Chọn địa chỉ sân trên bản đồ.");
            draft.getVenue().address(l.getDisplayAddress()).lat(l.getLatitude()).lng(l.getLongitude()).city(l.getCityCode());
            if(l.getAdministrativeArea()!=null)l.getAdministrativeArea().stream().filter(a->a.getLevel()==AdministrativeArea.LevelEnum.DISTRICT).findFirst().ifPresent(a->draft.getVenue().district(a.getName()));
        }
        var list=draft.getParkingLocations();if(list==null||list.isEmpty())return;
        if(list.size()>20)throw invalid("Tối đa 20 bãi xe.");
        long primary=list.stream().filter(p->Boolean.TRUE.equals(p.getIsPrimary())).count();
        if(primary==0)list.getFirst().isPrimary(true);else if(primary>1)throw invalid("Chỉ chọn một bãi chính.");
        for(var p:list){
            if(p.getName()==null||p.getName().isBlank()||p.getLocation()==null||p.getPriceStatus()==null)throw invalid("Bãi xe cần tên, vị trí và trạng thái giá.");
            VenueSetupClients.coordinates(p.getLocation().getLatitude(),p.getLocation().getLongitude());
            if(p.getPriceStatus()==ParkingSetupDraft.PriceStatusEnum.UNVERIFIED)p.priceVnd(null).priceUnit(null);
            else if(p.getPriceVnd()==null||p.getPriceVnd()<0||p.getPriceUnit()==null)throw invalid("Giá đã xác minh cần số tiền và đơn vị.");
            if(origin!=null){
                if(VenueSetupClients.distance(origin,p.getLocation())>3000)throw invalid("Bãi xe phải cách sân tối đa 3 km đường chim bay.");
                if(Boolean.TRUE.equals(p.getGuidanceApproved())&&p.getGuidanceInputHash()!=null&&!p.getGuidanceInputHash().equals(fingerprint(origin,p)))throw conflict();
            }
        }
    }
    public static String fingerprint(VenueLocationInput l,ParkingSetupDraft p){
        return sha((l.getLatitude()+"|"+l.getLongitude()+"|"+l.getDisplayAddress()+"|"+p.getName()+"|"+p.getLocation().getLatitude()+"|"+p.getLocation().getLongitude()+"|"+p.getLocation().getDisplayAddress()+"|"+p.getPriceStatus()+"|"+p.getPriceVnd()+"|"+p.getPriceUnit()+"|"+p.getVehicleType()+"|"+p.getOpeningHours()+"|"+p.getAdminNotes()).getBytes(StandardCharsets.UTF_8));
    }
    private static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private OffsetDateTime now(){return clock.instant().atOffset(ZoneOffset.UTC);}
    private static DomainException missing(){return new DomainException(ErrorCode.NOT_FOUND,"Không tìm thấy phiên hoặc PDF.");}
    private static DomainException invalid(String s){return new DomainException(ErrorCode.VALIDATION_ERROR,s);}
    private static DomainException conflict(){return new DomainException(ErrorCode.ACTION_STATE_INVALID,"Dữ liệu đã đổi; tải lại nháp hoặc tạo lại hướng dẫn.");}
}
