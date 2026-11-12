package vingo.catalog.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Component
public class SeatLayoutSvgParser {
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final Pattern ZONE_ID = Pattern.compile("ZONE__([A-Z][A-Z0-9-]*)__"
            + "(SEATED|STANDING)(?:__([0-9]+))?");
    private static final Pattern SEAT_ID = Pattern.compile("SEAT__([A-Z][A-Z0-9-]*)__([A-Za-z0-9-]+)__([0-9]+)");
    private static final Pattern LEVEL = Pattern.compile("-T([0-9]+)$");
    private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern PAINT = Pattern.compile("#[0-9A-Fa-f]{3,8}|[A-Za-z]+");
    private static final Set<String> ELEMENTS = Set.of(
            "svg", "g", "defs", "path", "rect", "circle", "use", "mask", "polygon", "polyline", "line");
    private static final Set<String> ATTRIBUTES = Set.of(
            "id", "viewBox", "x", "y", "width", "height", "cx", "cy", "r", "rx", "ry",
            "x1", "x2", "y1", "y2", "d", "points", "fill", "stroke", "stroke-width",
            "stroke-linejoin", "stroke-linecap", "fill-rule", "clip-rule", "mask",
            "maskUnits", "maskContentUnits", "opacity", "fill-opacity", "stroke-opacity", "transform");

    public enum ZoneKind {
        SEATED,
        STANDING
    }

    public record Bounds(
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height
    ) {}

   record ParsedSeat(
         String svgId,
         String rowLabel,
         int number,
         BigDecimal x,
         BigDecimal y
   ) {}

   record ParsedZone(
         String code,
         String label,
         String standCode,
         Integer level,
         ZoneKind kind,
         int capacity,
         String svgId,
         String contentSvgId,
         String pitchSideSvgId,
         Bounds bounds,
         List<ParsedSeat> seats
   ) {}

   record ParsedLayout(
         int schemaVersion,
         String svgContent,
         Bounds viewBox,
         List<ParsedZone> zones
   ) {}

    public ParsedLayout parse(byte[] input) {
        Document document = parseXml(input);
        Element root = document.getDocumentElement();
        Bounds viewBox = readViewBox(root);
        Map<String, Element> elements = validateElements(root);
        var geometry = new SeatLayoutSvgGeometry(elements);
        Map<String, ZoneDraft> zones = new LinkedHashMap<>();
        for (var entry : elements.entrySet()) {
            if (entry.getKey().startsWith("ZONE__")) {
                ZoneDraft zone = readZone(entry.getKey(), entry.getValue(), geometry);
                if (zones.putIfAbsent(zone.code(), zone) != null) {
                    throw invalid(entry.getKey(), "DUPLICATE_ZONE", "Mã khu đã được khai báo.");
                }
            }
        }
        if (zones.isEmpty()) {
            throw invalid("MISSING_ZONES", "SVG phải có ít nhất một khu ZONE__.");
        }
        for (var entry : elements.entrySet()) {
            if (entry.getKey().startsWith("SEAT__")) {
                readSeat(entry.getKey(), entry.getValue(), zones, geometry);
            }
        }
        List<ParsedZone> parsedZones = zones.values().stream()
                .map(zone -> finishZone(zone, elements))
                .toList();
        return new ParsedLayout(1, serialize(document), viewBox, parsedZones);
    }

    Bounds parseViewBox(byte[] input) {
        return readViewBox(parseXml(input).getDocumentElement());
    }

    private static Bounds readViewBox(Element root) {
        if (root == null
                || !"svg".equals(root.getLocalName())
                || !SVG.equals(root.getNamespaceURI())) {
            throw invalid("INVALID_SVG_ROOT", "Phần tử gốc phải là thẻ svg.");
        }

        String[] parts = root.getAttribute("viewBox").strip().split("[\\s,]+");
        if (parts.length != 4) {
            throw invalid("INVALID_VIEW_BOX", "viewBox phải có đủ x, y, width và height.");
        }

        try {
            var bounds = new Bounds(
                    SeatLayoutSvgGeometry.decimal(parts[0]),
                    SeatLayoutSvgGeometry.decimal(parts[1]),
                    SeatLayoutSvgGeometry.decimal(parts[2]),
                    SeatLayoutSvgGeometry.decimal(parts[3])
            );

            if (bounds.width().signum() <= 0 || bounds.height().signum() <= 0) {
                throw invalid("INVALID_VIEW_BOX", "Chiều rộng và chiều cao phải lớn hơn 0.");
            }

            return bounds;
        } catch (NumberFormatException ex) {
            throw invalid("INVALID_VIEW_BOX", "Các giá trị trong viewBox phải là số.");
        }
    }

    private static Map<String, Element> validateElements(Element root) {
        Map<String, Element> elements = new LinkedHashMap<>();
        var nodes = root.getElementsByTagName("*");
        validateElement(root, elements);
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if ("svg".equals(element.getLocalName())) {
                throw invalid(element.getAttribute("id"), "UNSUPPORTED_SVG_ELEMENT", "SVG lồng nhau không được hỗ trợ.");
            }
            validateElement(element, elements);
        }
        validateReferences(root, elements);
        for (int index = 0; index < nodes.getLength(); index++) {
            validateReferences((Element) nodes.item(index), elements);
        }
        return elements;
    }

    private static void validateElement(Element element, Map<String, Element> elements) {
        String id = element.getAttribute("id");
        if (!SVG.equals(element.getNamespaceURI()) || !ELEMENTS.contains(element.getLocalName())) {
            throw invalid(id, "UNSUPPORTED_SVG_ELEMENT", "Phần tử SVG không được hỗ trợ.");
        }
        if (element.hasAttribute("id") && (!ID.matcher(id).matches() || elements.putIfAbsent(id, element) != null)) {
            throw invalid(id, "INVALID_OR_DUPLICATE_ID", "ID layer không hợp lệ hoặc đã xuất hiện.");
        }
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            String name = attribute.getLocalName();
            String namespace = attribute.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)) {
                continue;
            }
            if ("use".equals(element.getLocalName()) && "href".equals(name)
                    && (namespace == null || XLINK.equals(namespace))) {
                continue;
            }
            if (namespace != null || !ATTRIBUTES.contains(name)) {
                throw invalid(id, "UNSUPPORTED_SVG_ATTRIBUTE", "Thuộc tính SVG không được hỗ trợ: " + name);
            }
            if (("fill".equals(name) || "stroke".equals(name))
                    && !PAINT.matcher(attribute.getNodeValue()).matches()) {
                throw invalid(id, "INVALID_PAINT", "Màu SVG không được chứa tham chiếu.");
            }
        }
    }

    private static void validateReferences(Element element, Map<String, Element> elements) {
        String id = element.getAttribute("id");
        if ("use".equals(element.getLocalName())) {
            String href = reference(element);
            Element target = href.startsWith("#") ? elements.get(href.substring(1)) : null;
            if (target == null || !Set.of("path", "rect", "circle", "polygon", "polyline", "line")
                    .contains(target.getLocalName()) || target.hasAttribute("mask")) {
                throw invalid(id, "INVALID_SVG_REFERENCE", "use phải trỏ đến hình nội bộ hợp lệ.");
            }
        }
        if (element.hasAttribute("mask")) {
            String mask = element.getAttribute("mask");
            Element target = mask.startsWith("url(#") && mask.endsWith(")")
                    ? elements.get(mask.substring(5, mask.length() - 1)) : null;
            if (target == null || !"mask".equals(target.getLocalName()) || inside(element, "mask", null)) {
                throw invalid(id, "INVALID_SVG_REFERENCE", "Mask phải trỏ đến mask nội bộ và không được lồng tham chiếu.");
            }
        }
    }

    static String reference(Element element) {
        String href = element.getAttribute("href");
        String xlink = element.getAttributeNS(XLINK, "href");
        if (!href.isEmpty() && !xlink.isEmpty() && !href.equals(xlink)) {
            throw invalid(element.getAttribute("id"), "INVALID_SVG_REFERENCE", "Hai thuộc tính href phải trỏ cùng một hình.");
        }
        return href.isEmpty() ? xlink : href;
    }

    private static ZoneDraft readZone(String id, Element element, SeatLayoutSvgGeometry geometry) {
        var match = ZONE_ID.matcher(id);
        if (!match.matches()) {
            throw invalid(id, "INVALID_ZONE_ID", "Tên layer khu không đúng quy ước SVG v1.");
        }
        String code = match.group(1);
        ZoneKind kind = ZoneKind.valueOf(match.group(2));
        String capacity = match.group(3);
        if ((kind == ZoneKind.STANDING) != (capacity != null)) {
            throw invalid(id, "INVALID_CAPACITY", "Chỉ khu STANDING phải khai báo sức chứa trong ID.");
        }
        var level = LEVEL.matcher(code);
        Integer floor = level.find() ? positiveInt(level.group(1), id, "INVALID_LEVEL") : null;
        Bounds bounds = geometry.bounds(element);
        return new ZoneDraft(code, floor, kind, capacity == null ? 0 : positiveInt(capacity, id, "INVALID_CAPACITY"),
                id, bounds, new ArrayList<>(), new HashSet<>());
    }

    private static void readSeat(String id, Element element, Map<String, ZoneDraft> zones,
            SeatLayoutSvgGeometry geometry) {
        var match = SEAT_ID.matcher(id);
        if (!match.matches() || !"circle".equals(element.getLocalName())) {
            throw invalid(id, "INVALID_SEAT_ID", "Ghế phải là circle có tên layer đúng quy ước.");
        }
        ZoneDraft zone = zones.get(match.group(1));
        if (zone == null || zone.kind() != ZoneKind.SEATED
                || !inside(element, "g", "CONTENT__" + match.group(1))) {
            throw invalid(id, "INVALID_SEAT_ZONE", "Ghế phải thuộc nhóm CONTENT của khu SEATED tương ứng.");
        }
        String row = match.group(2);
        int number = positiveInt(match.group(3), id, "INVALID_SEAT_NUMBER");
        if (!zone.keys().add(new SeatKey(row, number))) {
            throw invalid(id, "DUPLICATE_SEAT", "Trùng số ghế trong cùng hàng và khu.");
        }
        var point = geometry.seatCenter(element);
        Bounds bounds = zone.bounds();
        if (point.x().compareTo(bounds.x()) < 0 || point.y().compareTo(bounds.y()) < 0
                || point.x().compareTo(bounds.x().add(bounds.width())) > 0
                || point.y().compareTo(bounds.y().add(bounds.height())) > 0) {
            throw invalid(id, "SEAT_OUTSIDE_ZONE", "Tâm ghế nằm ngoài khung bao của khu.");
        }
        zone.seats().add(new ParsedSeat(id, row, number, point.x(), point.y()));
    }

    private static ParsedZone finishZone(ZoneDraft zone, Map<String, Element> elements) {
        String content = "CONTENT__" + zone.code();
        String pitch = "PITCH_SIDE__" + zone.code();
        if (zone.kind() == ZoneKind.SEATED && (zone.seats().isEmpty()
                || !elements.containsKey(content) || !elements.containsKey(pitch)
                || !inside(elements.get(pitch), "g", content))) {
            throw invalid(zone.svgId(), "MISSING_ZONE_DETAIL", "Khu SEATED cần ghế, nhóm CONTENT và cạnh hướng sân.");
        }
        return new ParsedZone(zone.code(), zone.code(), zone.code().substring(0, 1), zone.level(), zone.kind(),
                zone.kind() == ZoneKind.SEATED ? zone.seats().size() : zone.capacity(), zone.svgId(),
                elements.containsKey(content) ? content : null, elements.containsKey(pitch) ? pitch : null,
                zone.bounds(), List.copyOf(zone.seats()));
    }

    private static boolean inside(Element element, String tag, String id) {
        for (Node node = element.getParentNode(); node instanceof Element parent; node = parent.getParentNode()) {
            if (tag.equals(parent.getLocalName()) && (id == null || id.equals(parent.getAttribute("id")))) {
                return true;
            }
        }
        return false;
    }

    private static int positiveInt(String value, String id, String reason) {
        try {
            int number = Integer.parseInt(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // Lỗi nhập được trả kèm ID layer.
        }
        throw invalid(id, reason, "Giá trị phải là số nguyên dương.");
    }

    private static String serialize(Document document) {
        try {
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var writer = new StringWriter();
            factory.newTransformer().transform(new DOMSource(document.getDocumentElement()), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException ex) {
            throw new IllegalStateException("Không thể xuất SVG đã kiểm tra.", ex);
        }
    }

    private record SeatKey(String row, int number) {}

    private record ZoneDraft(String code, Integer level, ZoneKind kind, int capacity, String svgId, Bounds bounds,
            List<ParsedSeat> seats, Set<SeatKey> keys) {}

    private static Document parseXml(byte[] input) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true
            );
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setIgnoringComments(true);

            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(input));
            var walker = ((org.w3c.dom.traversal.DocumentTraversal) document).createTreeWalker(
                    document, org.w3c.dom.traversal.NodeFilter.SHOW_PROCESSING_INSTRUCTION, null, false);
            if (walker.nextNode() != null) {
                throw invalid("UNSUPPORTED_SVG_ELEMENT", "SVG không được chứa processing instruction.");
            }
            return document;
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("Không thể cấu hình XML parser.", ex);
        } catch (SAXException | IOException ex) {
            throw invalid("MALFORMED_XML", "Không đọc được XML. Hãy kiểm tra lại file SVG.");
        }
    }

    private static DomainException invalid(String reason, String message) {
        return invalid(null, reason, message);
    }

    static DomainException invalid(String layerId, String reason, String message) {
        Map<String, String> issue = layerId == null || layerId.isBlank()
                ? Map.of("reason", reason, "message", message)
                : Map.of("layer_id", layerId, "reason", reason, "message", message);
        return new DomainException(
                ErrorCode.INVALID_SEAT_LAYOUT,
                "Sơ đồ sân không hợp lệ.",
                Map.of("issues", List.of(issue))
        );
    }
}
