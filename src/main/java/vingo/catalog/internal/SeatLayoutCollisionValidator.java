package vingo.catalog.internal;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Kiểm tra collision khi file nhập có cả cổng và zone/ghế (COMBINED_V1). */
final class SeatLayoutCollisionValidator {
    static final double ZONE_TOLERANCE = 0.5;
    private SeatLayoutCollisionValidator() {}

    static void validate(Map<String, Element> elements, SeatLayoutSvgGeometry geometry) {
        validate(elements, geometry, elements, geometry);
    }

    static void validate(Map<String, Element> overview, Map<String, Element> detail) {
        validate(overview, new SeatLayoutSvgGeometry(overview), detail, new SeatLayoutSvgGeometry(detail));
    }

    private static void validate(Map<String, Element> overview, SeatLayoutSvgGeometry overviewGeometry,
            Map<String, Element> elements, SeatLayoutSvgGeometry geometry) {
        List<Map.Entry<String, Area>> gates = new ArrayList<>();
        overview.forEach((id, element) -> {
            if (id.startsWith("GATE__")) {
                Area area = overviewGeometry.area(element);
                if (area.isEmpty()) throw SeatLayoutSvgParser.invalid(id, "UNSUPPORTED_COLLISION_GEOMETRY", "Cổng cần vùng fill không rỗng.");
                gates.add(Map.entry(id, area));
            }
        });
        if (gates.isEmpty()) return;
        // Ưu tiên lỗi ghế cụ thể trước lỗi tổng thể của zone.
        for (var entry : elements.entrySet()) {
            if (!entry.getKey().startsWith("SEAT__")) continue;
            Area seat = geometry.seatArea(entry.getValue());
            var center = geometry.seatCenter(entry.getValue());
            String[] parts = entry.getKey().split("__");
            String zone = "ZONE__" + parts[1] + "__SEATED";
            for (var gate : gates) {
                Area overlap = intersection(seat, gate.getValue());
                if (!overlap.isEmpty() || gate.getValue().contains(center.x().doubleValue(), center.y().doubleValue())) {
                    throw collision(entry.getKey(), zone, gate.getKey(), "SEAT_OVERLAPS_GATE", SeatLayoutAreaMeasure.area(overlap), 0);
                }
            }
        }
        for (var entry : elements.entrySet()) {
            if (!entry.getKey().startsWith("ZONE__")) continue;
            Area zone = geometry.area(entry.getValue());
            if (zone.isEmpty()) throw SeatLayoutSvgParser.invalid(entry.getKey(), "UNSUPPORTED_COLLISION_GEOMETRY", "Zone cần vùng fill không rỗng.");
            for (var gate : gates) {
                Area overlap = intersection(zone, gate.getValue());
                double size = SeatLayoutAreaMeasure.area(overlap);
                if (size > ZONE_TOLERANCE) {
                    throw collision(entry.getKey(), entry.getKey(), gate.getKey(), "ZONE_OVERLAPS_GATE", size, ZONE_TOLERANCE);
                }
            }
        }
    }

    static Area intersection(Area first, Area second) {
        Area result = new Area(first);
        if (!first.getBounds2D().intersects(second.getBounds2D())) { result.reset(); return result; }
        result.intersect(second);
        return result;
    }

    private static DomainException collision(String layer, String zone, String gate, String reason, double size, double tolerance) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("layer_id", layer); issue.put("zone_id", zone); issue.put("gate_id", gate);
        issue.put("reason", reason); issue.put("intersection_area", size); issue.put("tolerance", tolerance);
        issue.put("message", "Vùng bán vé hoặc ghế chiếm vùng cổng; hãy sửa hình học SVG.");
        return new DomainException(ErrorCode.INVALID_SEAT_LAYOUT, "Sơ đồ sân không hợp lệ.", Map.of("issues", List.of(issue)));
    }
}
