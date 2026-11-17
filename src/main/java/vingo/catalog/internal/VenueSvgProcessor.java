package vingo.catalog.internal;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import vingo.shared.error.DomainException;
import vingo.catalog.internal.SeatLayoutSvgParser.*;

/** Hai lớp SVG dùng cùng hệ tọa độ; không suy đoán phép ghép từ tên file. */
@Component
public class VenueSvgProcessor {
    private final SeatLayoutSvgParser parser;
    public VenueSvgProcessor(SeatLayoutSvgParser parser) { this.parser = parser; }
    public record Marker(String code, String svgId, String direction, String labelId) {}
    public record Source(String role, String safeSvg, List<Map<String, Object>> issues) {
        public boolean valid() { return issues.isEmpty(); }
    }
    public record Bundle(String overviewSvg, ParsedLayout layout, List<Marker> gates,
                         List<Marker> areas, Map<String, String> zoneAreas) {}

    public Source inspect(byte[] bytes) {
        String safe = null;
        String role = "UNKNOWN";
        try {
            Document doc = SeatLayoutSvgParser.parseXml(bytes);
            var elements = SeatLayoutSvgParser.validateElements(doc.getDocumentElement());
            rejectDefinitionMarkers(elements);
            if (doc.getElementsByTagName("*").getLength() > 100_000) fail(null, "TOO_MANY_ELEMENTS", "SVG quá nhiều phần tử.");
            var box = SeatLayoutSvgParser.readViewBox(doc.getDocumentElement());
            origin(box);
            boolean detail = elements.keySet().stream().anyMatch(id -> id.startsWith("ZONE__") || id.startsWith("SEAT__"));
            boolean base = elements.keySet().stream().anyMatch(id -> id.equals("PITCH") || id.startsWith("GATE__") || id.startsWith("AREA__") || id.startsWith("WALKWAY__"));
            role = detail ? (base ? "COMBINED_V1" : "SEAT_LAYOUT") : base ? "OVERVIEW" : "UNKNOWN";
            String declared = doc.getDocumentElement().getAttribute("data-document-kind");
            if (!declared.isEmpty() && !declared.equals(role)) fail(null, "DOCUMENT_KIND_MISMATCH", "Khai báo loại SVG khác nội dung.");
            // Chỉ trả SVG sau khi toàn bộ allowlist và tham chiếu nội bộ đã qua kiểm tra.
            safe = SeatLayoutSvgParser.serialize(doc);
            if (role.equals("COMBINED_V1")) fail(null, "COMBINED_REQUIRES_SPLIT", "Tách file gộp thành SVG nền và SVG chi tiết trước khi nhập.");
            if (role.equals("UNKNOWN")) fail(null, "UNKNOWN_DOCUMENT_KIND", "Không tìm thấy marker nền hoặc zone/ghế.");
            if (role.equals("OVERVIEW")) overview(doc);
            else parser.parse(bytes);
            return new Source(role, safe, List.of());
        } catch (DomainException ex) {
            return new Source(role, safe, issues(ex));
        } catch (IllegalArgumentException ex) {
            return new Source(role,safe,List.of(Map.of("reason","UNSUPPORTED_COLLISION_GEOMETRY",
                    "message","Hình học vượt phạm vi số được hỗ trợ.")));
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> issues(DomainException ex) {
        Object value = ex.properties().get("issues");
        return value instanceof List<?> ? (List<Map<String, Object>>) value
                : List.of(Map.of("reason", ex.code().name(), "message", ex.getMessage()));
    }

    public Bundle combine(String overviewSvg, List<String> detailFiles, Map<String, String> requested) {
        try {return combineChecked(overviewSvg,detailFiles,requested);}
        catch(IllegalArgumentException ex) {throw SeatLayoutSvgParser.invalid(null,"UNSUPPORTED_COLLISION_GEOMETRY","Không phân giải được hình học hữu hạn của bộ SVG.");}
    }
    private Bundle combineChecked(String overviewSvg, List<String> detailFiles, Map<String, String> requested) {
        Document base = SeatLayoutSvgParser.parseXml(bytes(overviewSvg));
        var baseElements = overview(base);
        var box = SeatLayoutSvgParser.readViewBox(base.getDocumentElement());
        if (detailFiles.isEmpty()) fail(null, "MISSING_DETAIL", "Cần ít nhất một file chi tiết.");
        Document merged = SeatLayoutSvgParser.parseXml(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + box.width() + " " + box.height() + "\"/>"));
        Set<String> businessIds = new java.util.HashSet<>();
        for (int i = 0; i < detailFiles.size(); i++) {
            Document detail = SeatLayoutSvgParser.parseXml(bytes(detailFiles.get(i)));
            var ids = SeatLayoutSvgParser.validateElements(detail.getDocumentElement());
            rejectDefinitionMarkers(ids);
            var b = SeatLayoutSvgParser.readViewBox(detail.getDocumentElement());
            if (!same(box, b)) fail(null, "VIEW_BOX_MISMATCH", "File chi tiết phải có cùng viewBox với nền.");
            if (ids.keySet().stream().anyMatch(id -> id.equals("PITCH") || id.startsWith("GATE__") || id.startsWith("AREA__") || id.startsWith("WALKWAY__")))
                fail(null, "INVALID_DETAIL", "File chi tiết không được chứa nền/cổng.");
            Map<String,String> renamed = new HashMap<>();
            String prefix="FILE_"+i+"__";
            boolean clashes;
            do {
                String candidate=prefix;
                clashes=baseElements.keySet().stream().anyMatch(id->id.startsWith(candidate));
                if(clashes) prefix="V2_"+prefix;
            } while(clashes);
            for (String id : ids.keySet()) {
                if (business(id)) { if (baseElements.containsKey(id)||!businessIds.add(id)) fail(id, "DUPLICATE_ID", "ID nghiệp vụ trùng giữa các file."); }
                else renamed.put(id,prefix+id);
            }
            Element group = (Element) merged.importNode(detail.getDocumentElement(), true);
            // Giữ style/transform của root từng file bằng g, không tạo svg lồng nhau.
            group = (Element) merged.renameNode(group, "http://www.w3.org/2000/svg", "g");
            group.removeAttribute("viewBox"); group.removeAttribute("width"); group.removeAttribute("height");
            group.removeAttribute("data-schema-version"); group.removeAttribute("data-document-kind");
            rewrite(group, renamed);
            var children = group.getElementsByTagName("*");
            for (int n=0;n<children.getLength();n++) rewrite((Element) children.item(n), renamed);
            merged.getDocumentElement().appendChild(group);
        }
        String detailSvg = SeatLayoutSvgParser.serialize(merged);
        if (bytes(detailSvg).length > 10*1024*1024) fail(null, "MERGED_SVG_TOO_LARGE", "SVG hợp nhất vượt 10 MiB.");
        var parsed = parser.parse(bytes(detailSvg));
        var detailElements = SeatLayoutSvgParser.validateElements(merged.getDocumentElement());
        var geometry = new SeatLayoutSvgGeometry(detailElements);
        var baseGeometry = new SeatLayoutSvgGeometry(baseElements);
        Area canvas = new Area(new Rectangle2D.Double(0,0,box.width().doubleValue(),box.height().doubleValue()));
        Map<String,Area> zones = new LinkedHashMap<>();
        Map<String,String> mappings = new LinkedHashMap<>();
        List<Marker> areas = markers(baseElements, "AREA__");
        for (var zone : parsed.zones()) {
            Area shape = geometry.area(detailElements.get(zone.svgId()));
            if (shape.isEmpty()) fail(zone.svgId(), "EMPTY_ZONE", "Zone cần có diện tích fill.");
            contained(shape, canvas, zone.svgId(), "ZONE_OUTSIDE_VIEW_BOX");
            for (var entry : zones.entrySet()) if (intersects(shape,entry.getValue())) fail(zone.svgId(),"ZONE_OVERLAPS_ZONE","Zone giao zone " + entry.getKey());
            zones.put(zone.code(),shape);
            String chosen = requested == null ? null : requested.get(zone.code());
            List<String> candidates = new ArrayList<>();
            for (var area : areas) if (inside(shape,baseGeometry.area(baseElements.get(area.svgId())))) candidates.add(area.code());
            if (!areas.isEmpty()) {
                if (chosen == null && candidates.size()==1) chosen=candidates.getFirst();
                if (chosen == null || !candidates.contains(chosen)) fail(zone.svgId(),"INVALID_AREA_MAPPING","Zone cần nằm hoàn toàn trong AREA được chọn, mapping phải rõ ràng.");
                mappings.put(zone.code(),chosen);
            } else if (chosen != null) fail(zone.svgId(),"INVALID_AREA_MAPPING","Nền không có AREA được chọn.");
        }
        if (requested != null && !zones.keySet().containsAll(requested.keySet())) fail(null,"UNKNOWN_ZONE_MAPPING","Mapping chứa zone không tồn tại.");
        List<SeatShape> seats = new ArrayList<>();
        for (var zone : parsed.zones()) for (var seat : zone.seats()) {
            Area shape = geometry.seatArea(detailElements.get(seat.svgId()));
            contained(shape, canvas, seat.svgId(), "SEAT_OUTSIDE_VIEW_BOX");
            contained(shape, zones.get(zone.code()), seat.svgId(), "SEAT_OUTSIDE_ZONE");
            seats.add(new SeatShape(seat.svgId(),shape,shape.getBounds2D()));
        }
        seats.sort(Comparator.comparingDouble(s -> s.bounds().getMinX()));
        List<SeatShape> active=new ArrayList<>();
        for (var seat : seats) {
            active.removeIf(other -> other.bounds().getMaxX() <= seat.bounds().getMinX());
            for (var other : active) if (seat.bounds().intersects(other.bounds()) && intersects(seat.shape(),other.shape()))
                fail(seat.id(),"SEAT_OVERLAPS_SEAT","Ghế giao ghế " + other.id());
            active.add(seat);
        }
        SeatLayoutCollisionValidator.validate(baseElements,detailElements);
        return new Bundle(overviewSvg,new ParsedLayout(2,detailSvg,box,parsed.zones()),markers(baseElements,"GATE__"),areas,Map.copyOf(mappings));
    }
    private record SeatShape(String id, Area shape, Rectangle2D bounds) {}
    private static Map<String,Element> overview(Document doc) {
        var root=doc.getDocumentElement(); var box=SeatLayoutSvgParser.readViewBox(root); origin(box);
        var ids=SeatLayoutSvgParser.validateElements(root); rejectDefinitionMarkers(ids); var geom=new SeatLayoutSvgGeometry(ids);
        if (ids.keySet().stream().anyMatch(VenueSvgProcessor::business)) fail(null,"INVALID_OVERVIEW","Nền không chứa zone/ghế/CONTENT/PITCH_SIDE của lớp chi tiết.");
        var gates=markers(ids,"GATE__"); if(gates.isEmpty()) fail(null,"MISSING_GATES","Nền cần ít nhất một cổng.");
        Area canvas=new Area(new Rectangle2D.Double(0,0,box.width().doubleValue(),box.height().doubleValue()));
        List<Marker> shapes=new ArrayList<>(gates); shapes.addAll(markers(ids,"AREA__"));
        for(var m:shapes) { Area a=geom.area(ids.get(m.svgId())); if(a.isEmpty()) fail(m.svgId(),"EMPTY_SHAPE","Marker cần có hình học fill."); contained(a,canvas,m.svgId(),"SHAPE_OUTSIDE_VIEW_BOX"); }
        return ids;
    }
    private static List<Marker> markers(Map<String,Element> ids,String prefix) {
        List<Marker> result=new ArrayList<>(); Set<String> codes=new java.util.HashSet<>();
        for(String id:ids.keySet()) if(id.startsWith(prefix)) {
            String code=id.substring(prefix.length()),direction="BOTH";
            if(prefix.equals("GATE__")) for(String d:List.of("IN","OUT","BOTH")) if(code.endsWith("__"+d)) {code=code.substring(0,code.length()-d.length()-2);direction=d;break;}
            String pattern=prefix.equals("GATE__") ? "[A-Z0-9][A-Z0-9-]*(__[0-9]+)?" : "[A-Z][A-Z0-9-]*";
            if(!code.matches(pattern)||!codes.add(code)) fail(id,"INVALID_OR_DUPLICATE_CODE","Mã marker sai hoặc trùng.");
            result.add(new Marker(code,id,direction,ids.containsKey("GATE_LABEL__"+code)?"GATE_LABEL__"+code:null));
        }
        return List.copyOf(result);
    }
    private static boolean business(String id) {return List.of("ZONE__","CONTENT__","SEAT__","PITCH_SIDE__").stream().anyMatch(id::startsWith);}
    private static void rejectDefinitionMarkers(Map<String,Element> elements) {
        for(var entry:elements.entrySet()) {
            String id=entry.getKey();
            if(!business(id)&&!id.equals("PITCH")&&!id.startsWith("GATE__")&&!id.startsWith("AREA__")&&!id.startsWith("WALKWAY__")) continue;
            for(Node p=entry.getValue().getParentNode();p instanceof Element ancestor;p=p.getParentNode()) {
                if(Set.of("defs","mask").contains(ancestor.getLocalName())) fail(id,"NON_RENDERED_MARKER","Marker nghiệp vụ phải ở lớp hiển thị, không nằm trong defs/mask.");
            }
        }
    }
    private static void rewrite(Element e,Map<String,String> names) {
        if(names.containsKey(e.getAttribute("id"))) e.setAttribute("id",names.get(e.getAttribute("id")));
        var attrs=e.getAttributes(); for(int i=0;i<attrs.getLength();i++) {Node a=attrs.item(i);String v=a.getNodeValue();
            if(v.startsWith("#") && names.containsKey(v.substring(1))) a.setNodeValue("#"+names.get(v.substring(1)));
            else if(v.startsWith("url(#")&&v.endsWith(")")&&names.containsKey(v.substring(5,v.length()-1))) a.setNodeValue("url(#"+names.get(v.substring(5,v.length()-1))+")");
        }
    }
    private static void origin(Bounds b) {if(b.x().signum()!=0||b.y().signum()!=0) fail(null,"INVALID_VIEW_BOX_ORIGIN","viewBox phải bắt đầu tại 0 0.");}
    private static boolean same(Bounds a,Bounds b) {return a.x().compareTo(b.x())==0&&a.y().compareTo(b.y())==0&&a.width().compareTo(b.width())==0&&a.height().compareTo(b.height())==0;}
    private static boolean inside(Area shape,Area container) {Area rest=new Area(shape);rest.subtract(container);return rest.isEmpty()||SeatLayoutAreaMeasure.area(rest)<=1e-8;}
    private static void contained(Area shape,Area container,String id,String reason) {if(!inside(shape,container)) fail(id,reason,"Hình học phải nằm hoàn toàn trong vùng tương ứng.");}
    private static boolean intersects(Area a,Area b) {Area overlap=new Area(a);overlap.intersect(b);return !overlap.isEmpty()&&SeatLayoutAreaMeasure.area(overlap)>1e-8;}
    private static byte[] bytes(String s) {return s.getBytes(StandardCharsets.UTF_8);}
    private static void fail(String id,String reason,String message) {throw SeatLayoutSvgParser.invalid(id,reason,message);}
}
