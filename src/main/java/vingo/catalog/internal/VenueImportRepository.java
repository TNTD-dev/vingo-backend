package vingo.catalog.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.*;

/** Lưu nháp riêng; không tạo venue/layout chưa hoàn chỉnh trong lúc upload. */
@Repository
public class VenueImportRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SeatLayoutRepository layouts;
    public VenueImportRepository(JdbcClient jdbc, JsonMapper json, SeatLayoutRepository layouts) {this.jdbc=jdbc;this.json=json;this.layouts=layouts;}
    public Optional<VenueLocationInput> venueLocation(UUID id) {
        return jdbc.sql("""
                SELECT COALESCE(location, jsonb_build_object('display_address', address,
                    'latitude', lat, 'longitude', lng, 'source', 'LEGACY'))::text
                FROM venue WHERE id = :id AND deleted_at IS NULL
                """).param("id", id).query(String.class)
                .optional().map(value -> json.readValue(value, VenueLocationInput.class));
    }

    public record FileSource(UUID id, String filename, String checksum, int size,
                             String role, String safeSvg, String status, String issues) {}
    public record Snapshot(UUID id, UUID ownerId, UUID venueId, String status, long revision,
                           String step, VenueImportDraft draft, OffsetDateTime expiresAt,
                           UUID completedVenue, UUID completedLayout) {}
    public UUID create(UUID owner, UUID venue, VenueImportDraft draft, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO venue_import_session(owner_id,target_venue_id,draft_data,created_at,updated_at,expires_at)
                VALUES(:owner,:venue,CAST(:draft AS jsonb),:now,:now,:expiry) RETURNING id
                """).param("owner",owner).param("venue",venue).param("draft",json.writeValueAsString(draft))
                .param("now",now).param("expiry",now.plusDays(30)).query(UUID.class).single();
    }
    public Optional<Snapshot> find(UUID id, UUID owner, boolean lock) {
        return jdbc.sql("SELECT * FROM venue_import_session WHERE id=:id AND owner_id=:owner"+(lock?" FOR UPDATE":""))
                .param("id",id).param("owner",owner).query((rs,n)->new Snapshot(id,owner,
                        rs.getObject("target_venue_id",UUID.class),rs.getString("status"),rs.getLong("revision"),
                        rs.getString("step"),json.readValue(rs.getString("draft_data"),VenueImportDraft.class),
                        rs.getObject("expires_at",OffsetDateTime.class),rs.getObject("completed_venue_id",UUID.class),
                        rs.getObject("completed_layout_id",UUID.class))).optional();
    }
    public List<UUID> list(UUID owner, UUID after, int limit, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT id FROM venue_import_session WHERE owner_id=:owner AND status='DRAFT' AND expires_at>:now
                AND (CAST(:after AS uuid) IS NULL OR id<:after) ORDER BY id DESC LIMIT :limit
                """).param("owner",owner).param("after",after).param("limit",limit).param("now",now).query(UUID.class).list();
    }
    public List<FileSource> files(UUID id) {
        return jdbc.sql("SELECT id,original_filename,checksum,size_bytes,role,sanitized_svg,validation_status,issues FROM venue_import_file WHERE session_id=:id ORDER BY id").param("id",id)
                .query((rs,n)->new FileSource(rs.getObject("id",UUID.class),rs.getString("original_filename"),
                        rs.getString("checksum"),rs.getInt("size_bytes"),rs.getString("role"),
                        rs.getString("sanitized_svg"),rs.getString("validation_status"),rs.getString("issues"))).list();
    }
    public void insertFile(UUID session, String filename, byte[] source, VenueSvgProcessor.Source parsed) {
        jdbc.sql("""
                INSERT INTO venue_import_file(session_id,original_filename,source_bytes,role,layout_key,
                    sanitized_svg,validation_status,schema_version,issues)
                VALUES(:session,:filename,:source,:role,:key,:safe,:status,2,CAST(:issues AS jsonb))
                ON CONFLICT(session_id,checksum) DO NOTHING
                """).param("session",session).param("filename",filename).param("source",source)
                .param("role",parsed.role()).param("key",parsed.role().equals("SEAT_LAYOUT")?session:null)
                .param("safe",parsed.safeSvg()).param("status",parsed.valid()?"VALIDATED":"INVALID")
                .param("issues",json.writeValueAsString(parsed.issues())).update();
    }
    public boolean deleteFile(UUID session, UUID file) {
        return jdbc.sql("DELETE FROM venue_import_file WHERE id=:id AND session_id=:session")
                .param("id",file).param("session",session).update()==1;
    }
    public void patch(UUID id,String step,VenueImportDraft draft,OffsetDateTime now) {
        jdbc.sql("UPDATE venue_import_session SET step=:step,draft_data=CAST(:draft AS jsonb),updated_at=:now,expires_at=:expiry WHERE id=:id")
                .param("id",id).param("step",step).param("draft",json.writeValueAsString(draft)).param("now",now).param("expiry",now.plusDays(30)).update();
    }
    public void close(UUID id,String status,UUID venue,UUID layout,OffsetDateTime now) {
        jdbc.sql("UPDATE venue_import_session SET status=:status,completed_venue_id=:venue,completed_layout_id=:layout,updated_at=:now WHERE id=:id")
                .param("id",id).param("status",status).param("venue",venue).param("layout",layout).param("now",now).update();
    }
    public boolean lockVenue(UUID id) {
        return jdbc.sql("SELECT id FROM venue WHERE id=:id AND deleted_at IS NULL AND type='STADIUM' FOR UPDATE")
                .param("id",id).query(UUID.class).optional().isPresent();
    }
    public boolean activeVenue(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM venue WHERE id=:id AND deleted_at IS NULL AND type='STADIUM')")
                .param("id",id).query(Boolean.class).single();
    }
    public Optional<VenueOverview> overview(UUID id,boolean lock) {
        return jdbc.sql("SELECT o.* FROM venue_overview o JOIN venue v ON v.id=o.venue_id WHERE o.id=:id AND v.deleted_at IS NULL"+(lock?" FOR UPDATE OF o":""))
                .param("id",id).query((rs,n)->new VenueOverview().id(id).venueId(rs.getObject("venue_id",UUID.class))
                        .name(rs.getString("name")).version(rs.getLong("version"))
                        .status(VenueOverview.StatusEnum.fromValue(rs.getString("status"))).svgContent(rs.getString("svg_content"))
                        .viewBox(new SeatLayoutBounds(rs.getBigDecimal("view_box_x"),rs.getBigDecimal("view_box_y"),rs.getBigDecimal("view_box_width"),rs.getBigDecimal("view_box_height"))))
                .optional().map(o->o.gates(gates(id)).areas(areas(id)));
    }
    public List<UUID> overviews(UUID venue,UUID after,int limit) {
        return jdbc.sql("SELECT id FROM venue_overview WHERE venue_id=:venue AND (CAST(:after AS uuid) IS NULL OR id<:after) ORDER BY id DESC LIMIT :limit")
                .param("venue",venue).param("after",after).param("limit",limit).query(UUID.class).list();
    }
    public UUID overviewIdForLayout(UUID layout) {
        return jdbc.sql("SELECT overview_id FROM seat_layout WHERE id=:id").param("id",layout).query(UUID.class).single();
    }
    public boolean certified(UUID layout) {
        return jdbc.sql("SELECT validation_hash IS NOT NULL AND validation_hash=catalog_layout_hash(id) FROM seat_layout WHERE id=:id")
                .param("id",layout).query(Boolean.class).single();
    }
    public void publishOverview(UUID id,OffsetDateTime now) {
        jdbc.sql("UPDATE venue_overview SET status='PUBLISHED',published_at=:now WHERE id=:id AND status='DRAFT'")
                .param("id",id).param("now",now).update();
    }
    private List<OverviewGate> gates(UUID id) {
        return jdbc.sql("SELECT * FROM venue_gate WHERE overview_id=:id ORDER BY code").param("id",id)
                .query((rs,n)->new OverviewGate().id(rs.getObject("id",UUID.class)).code(rs.getString("code")).name(rs.getString("name"))
                        .svgId(rs.getString("svg_id")).direction(OverviewGate.DirectionEnum.fromValue(rs.getString("direction")))).list();
    }
    private List<OverviewArea> areas(UUID id) {
        return jdbc.sql("SELECT * FROM venue_overview_area WHERE overview_id=:id ORDER BY code").param("id",id)
                .query((rs,n)->new OverviewArea().id(rs.getObject("id",UUID.class)).code(rs.getString("code")).name(rs.getString("name")).svgId(rs.getString("svg_id"))).list();
    }
    public UUID insertOverview(UUID venue,String name,VenueSvgProcessor.Bundle bundle) {
        var box=bundle.layout().viewBox();
        UUID id=jdbc.sql("""
                INSERT INTO venue_overview(venue_id,name,svg_content,view_box_width,view_box_height)
                VALUES(:venue,:name,:svg,:width,:height) RETURNING id
                """).param("venue",venue).param("name",name).param("svg",bundle.overviewSvg())
                .param("width",box.width()).param("height",box.height()).query(UUID.class).single();
        for(var g:bundle.gates()) jdbc.sql("INSERT INTO venue_gate(overview_id,code,name,svg_id,direction,label_svg_id) VALUES(:id,:code,:code,:svg,:direction,:label)")
                .param("id",id).param("code",g.code()).param("svg",g.svgId()).param("direction",g.direction()).param("label",g.labelId()).update();
        for(var a:bundle.areas()) jdbc.sql("INSERT INTO venue_overview_area(overview_id,code,name,svg_id) VALUES(:id,:code,:code,:svg)")
                .param("id",id).param("code",a.code()).param("svg",a.svgId()).update();
        return id;
    }
    public UUID insertLayout(UUID venue,UUID overview,String type,String name,VenueSvgProcessor.Bundle bundle) {
        UUID id=jdbc.sql("INSERT INTO seat_layout(venue_id,overview_id,layout_type,name) VALUES(:venue,:overview,:type,:name) RETURNING id")
                .param("venue",venue).param("overview",overview).param("type",type).param("name",name).query(UUID.class).single();
        layouts.insertSvg(id,bundle.layout());
        UUID seatType=layouts.standardSeatType();
        for(var z:bundle.layout().zones()) {
            UUID zone=layouts.insertZone(id,z);
            if(bundle.zoneAreas().containsKey(z.code())) mapZone(zone,overview,bundle.zoneAreas().get(z.code()));
            if(!z.seats().isEmpty()) layouts.insertSeats(zone,seatType,z.seats());
        }
        return id;
    }
    public void mapZone(UUID zone, UUID overview,String code) {
        jdbc.sql("UPDATE zone SET overview_area_id=(SELECT id FROM venue_overview_area WHERE overview_id=:overview AND code=:code) WHERE id=:id")
                .param("id",zone).param("overview",overview).param("code",code).update();
    }
    public void certify(UUID overview,UUID layout,OffsetDateTime now) {
        jdbc.sql("UPDATE venue_overview SET validation_hash=catalog_overview_hash(id),validation_engine='svg-v2-area-1',validated_at=:now WHERE id=:id AND status='DRAFT'")
                .param("id",overview).param("now",now).update();
        jdbc.sql("UPDATE seat_layout SET validation_hash=catalog_layout_hash(id),validation_engine='svg-v2-area-1',validated_at=:now WHERE id=:id AND status='DRAFT'")
                .param("id",layout).param("now",now).update();
    }
    public int cleanup(OffsetDateTime now,int batch) {
        return jdbc.sql("""
                DELETE FROM venue_import_session WHERE id IN
                (SELECT id FROM venue_import_session WHERE expires_at<=:now ORDER BY expires_at FOR UPDATE SKIP LOCKED LIMIT :batch)
                """).param("now",now).param("batch",batch).update();
    }

    public void persistSetup(UUID session,UUID venue,VenueImportDraft draft) {
        if(draft.getVenue()!=null && draft.getVenue().getLocation()!=null) {
            jdbc.sql("UPDATE venue SET location=CAST(:location AS jsonb) WHERE id=:id")
                .param("location",json.writeValueAsString(draft.getVenue().getLocation())).param("id",venue).update();
        }
        if(draft.getParkingLocations()!=null) {
            int ordinal=0;
            for(var p:draft.getParkingLocations()) {
                jdbc.sql("""
                    INSERT INTO parking_locations(venue_id,setup_session_id,ordinal,name,location,is_primary,price_status,price_vnd,price_unit,data)
                    VALUES(:venue,:session,:ordinal,:name,CAST(:location AS jsonb),:primary,:status,:price,:unit,CAST(:data AS jsonb))
                    ON CONFLICT(setup_session_id,ordinal) DO NOTHING
                    """).param("venue",venue).param("session",session).param("ordinal",ordinal++).param("name",p.getName())
                    .param("location",json.writeValueAsString(p.getLocation())).param("primary",Boolean.TRUE.equals(p.getIsPrimary()))
                    .param("status",p.getPriceStatus().getValue()).param("price",p.getPriceVnd()).param("unit",p.getPriceUnit()==null?null:p.getPriceUnit().getValue())
                    .param("data",json.writeValueAsString(p)).update();
            }
        }
    }
}
