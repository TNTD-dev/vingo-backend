package vingo.catalog.internal;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Tập SVG fill hình học có kiểm soát; không mô phỏng CSS hoặc mask bán trong suốt. */
final class SeatLayoutSvgShapeResolver {
    private static final int MAX_DEPTH = 64;
    private final Map<String, Element> elements;

    SeatLayoutSvgShapeResolver(Map<String, Element> elements) { this.elements = elements; }

    Area area(Element element) {
        try {
            List<Element> parents = new ArrayList<>();
            for (Node n = element.getParentNode(); n instanceof Element parent; n = parent.getParentNode()) {
                if (parents.size() >= MAX_DEPTH || parent.hasAttribute("mask")) throw unsupported();
                parents.add(parent);
            }
            Style style = Style.DEFAULT;
            AffineTransform parentTransform = new AffineTransform();
            for (Element parent : parents.reversed()) {
                style = style.with(parent, false);
                parentTransform.concatenate(SeatLayoutSvgGeometry.readTransform(parent.getAttribute("transform")));
            }
            Area result = render(element, style, 0, false);
            SeatLayoutSvgGeometry.requireFinite(parentTransform);
            result.transform(parentTransform);
            finite(result);
            return result;
        } catch (IllegalArgumentException ex) {
            throw SeatLayoutSvgParser.invalid(element.getAttribute("id"), "UNSUPPORTED_COLLISION_GEOMETRY",
                    "Không phân giải được hình học collision: " + ex.getMessage());
        }
    }

    private Area render(Element element, Style inherited, int depth, boolean maskPaint) {
        if (element == null || depth > MAX_DEPTH) throw unsupported();
        String tag = element.getLocalName();
        if (List.of("defs", "mask", "line").contains(tag)) return new Area();
        Style style = inherited.with(element, maskPaint);
        AffineTransform transform = SeatLayoutSvgGeometry.readTransform(element.getAttribute("transform"));
        Area result;
        switch (tag) {
            case "svg", "g" -> result = children(element, style, depth + 1, maskPaint);
            case "use" -> {
                String href = SeatLayoutSvgParser.reference(element);
                if (!href.startsWith("#")) throw unsupported();
                Element target = elements.get(href.substring(1));
                if (target == null || !List.of("path", "rect", "polygon", "polyline", "circle", "line").contains(target.getLocalName())) {
                    throw unsupported();
                }
                result = render(target, style, depth + 1, maskPaint);
                result.transform(AffineTransform.getTranslateInstance(number(element, "x", 0), number(element, "y", 0)));
            }
            default -> {
                Shape shape = shape(element, style.rule());
                result = style.visible() && style.fillOpacity() != 0 && !"none".equals(style.fill()) ? new Area(shape) : new Area();
            }
        }
        if (!style.visible()) result.reset();
        if (element.hasAttribute("mask")) {
            if (maskPaint) throw unsupported();
            result = masked(element, result, depth + 1);
        }
        result.transform(transform);
        finite(result);
        return result;
    }

    private Area children(Element element, Style style, int depth, boolean maskPaint) {
        Area result = new Area();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element child)) continue;
            if (child.getAttribute("id").startsWith("GATE_LABEL__")) continue;
            Area painted = render(child, style, depth, maskPaint);
            if (maskPaint) {
                Style childStyle = style.with(child, true);
                if ("use".equals(child.getLocalName())) {
                    String reference = SeatLayoutSvgParser.reference(child);
                    Element target = reference.startsWith("#") ? elements.get(reference.substring(1)) : null;
                    if (target == null) throw unsupported();
                    childStyle = childStyle.with(target, true);
                }
                String fill = childStyle.fill();
                if ("g".equals(child.getLocalName())) throw unsupported();
                if (black(fill)) result.subtract(painted);
                else if (white(fill) || "none".equals(fill)) result.add(painted);
                else throw unsupported();
            } else result.add(painted);
        }
        return result;
    }

    private Area masked(Element subject, Area input, int depth) {
        if (List.of("g", "svg").contains(subject.getLocalName())) throw unsupported();
        String reference = subject.getAttribute("mask");
        if (!reference.startsWith("url(#") || !reference.endsWith(")")) throw unsupported();
        Element mask = elements.get(reference.substring(5, reference.length() - 1));
        if (mask == null || !"mask".equals(mask.getLocalName()) || mask.hasAttribute("mask")) throw unsupported();
        if (mask.hasAttribute("mask-type") && !"luminance".equals(mask.getAttribute("mask-type"))) throw unsupported();
        if (mask.hasAttribute("maskContentUnits") && !"userSpaceOnUse".equals(mask.getAttribute("maskContentUnits"))) throw unsupported();
        // Tập hỗ trợ: vùng mặc định objectBoundingBox hoặc userSpaceOnUse với số tuyệt đối.
        Rectangle2D bounds = input.getBounds2D();
        if (mask.hasAttribute("transform")) throw unsupported();
        List<Element> maskParents = new ArrayList<>();
        for (Node n = mask.getParentNode(); n instanceof Element parent; n = parent.getParentNode()) {
            if (maskParents.size() >= MAX_DEPTH) throw unsupported();
            maskParents.add(parent);
        }
        Style maskStyle = Style.DEFAULT;
        for (Element parent : maskParents.reversed()) maskStyle = maskStyle.with(parent, true);
        Area maskArea = children(mask, maskStyle.with(mask, true), depth + 1, true);
        // Nét viền fill=none không đóng góp vùng collision, dù có mask trang trí.
        if (input.isEmpty()) return new Area();
        Rectangle2D region;
        String units = mask.hasAttribute("maskUnits") ? mask.getAttribute("maskUnits") : "objectBoundingBox";
        if ("objectBoundingBox".equals(units)) {
            region = new Rectangle2D.Double(bounds.getX() + fraction(mask, "x", -.1) * bounds.getWidth(),
                    bounds.getY() + fraction(mask, "y", -.1) * bounds.getHeight(),
                    fraction(mask, "width", 1.2) * bounds.getWidth(), fraction(mask, "height", 1.2) * bounds.getHeight());
        } else if ("userSpaceOnUse".equals(units) && List.of("x", "y", "width", "height").stream().allMatch(mask::hasAttribute)) {
            region = new Rectangle2D.Double(number(mask, "x", 0), number(mask, "y", 0),
                    positive(mask, "width"), positive(mask, "height"));
        } else throw unsupported();
        if (region.getWidth() <= 0 || region.getHeight() <= 0) throw unsupported();
        maskArea.intersect(new Area(region));
        Area result = new Area(input);
        result.intersect(maskArea);
        return result;
    }

    private static Shape shape(Element e, int rule) {
        return switch (e.getLocalName()) {
            case "path" -> {
                Path2D path = SeatLayoutSvgGeometry.path(e.getAttribute("d"));
                path.setWindingRule(rule);
                yield path;
            }
            case "polygon", "polyline" -> {
                Path2D path = SeatLayoutSvgGeometry.points(e);
                path.setWindingRule(rule);
                yield path;
            }
            case "circle" -> {
                double r = positive(e, "r");
                yield new Ellipse2D.Double(number(e, "cx", 0) - r, number(e, "cy", 0) - r, r * 2, r * 2);
            }
            case "rect" -> {
                double w = positive(e, "width"), h = positive(e, "height");
                double rx = number(e, "rx", number(e, "ry", 0)), ry = number(e, "ry", rx);
                if (rx < 0 || ry < 0) throw unsupported();
                yield new RoundRectangle2D.Double(number(e, "x", 0), number(e, "y", 0), w, h,
                        Math.min(rx * 2, w), Math.min(ry * 2, h));
            }
            default -> throw unsupported();
        };
    }

    private record Style(String fill, int rule, boolean visible, double fillOpacity, String stroke) {
        static final Style DEFAULT = new Style("black", Path2D.WIND_NON_ZERO, true, 1, "none");
        Style with(Element e, boolean mask) {
            String value = e.hasAttribute("fill") && !"inherit".equals(e.getAttribute("fill")) ? e.getAttribute("fill") : fill;
            String newStroke = e.hasAttribute("stroke") && !"inherit".equals(e.getAttribute("stroke")) ? e.getAttribute("stroke") : stroke;
            String winding = e.getAttribute("fill-rule");
            int newRule = winding.isEmpty() || "inherit".equals(winding) ? rule : switch (winding) {
                case "evenodd" -> Path2D.WIND_EVEN_ODD;
                case "nonzero" -> Path2D.WIND_NON_ZERO;
                default -> throw unsupported();
            };
            double opacity = number(e, "opacity", 1), newFillOpacity = number(e, "fill-opacity", fillOpacity);
            if (opacity < 0 || opacity > 1 || newFillOpacity < 0 || newFillOpacity > 1) throw unsupported();
            if (mask && ((opacity != 0 && opacity != 1) || (newFillOpacity != 0 && newFillOpacity != 1)
                    || !"none".equals(newStroke))) throw unsupported();
            return new Style(value, newRule, visible && opacity != 0, newFillOpacity, newStroke);
        }
    }

    private static boolean white(String fill) { return List.of("white", "#fff", "#ffffff").contains(fill.toLowerCase(java.util.Locale.ROOT)); }
    private static boolean black(String fill) { return List.of("black", "#000", "#000000").contains(fill.toLowerCase(java.util.Locale.ROOT)); }
    private static double fraction(Element e, String key, double fallback) {
        if (!e.hasAttribute(key)) return fallback;
        String value = e.getAttribute(key);
        return value.endsWith("%") ? SeatLayoutSvgGeometry.decimal(value.substring(0, value.length() - 1)).doubleValue() / 100 : number(e, key, fallback);
    }
    private static double number(Element e, String key, double fallback) {
        return e.hasAttribute(key) ? SeatLayoutSvgGeometry.decimal(e.getAttribute(key)).doubleValue() : fallback;
    }
    private static double positive(Element e, String key) {
        double value = number(e, key, 0);
        if (value <= 0) throw unsupported();
        return value;
    }
    private static void finite(Area area) {
        Rectangle2D b = area.getBounds2D();
        if (!Double.isFinite(b.getMinX()) || !Double.isFinite(b.getMinY()) || !Double.isFinite(b.getMaxX()) || !Double.isFinite(b.getMaxY())) throw unsupported();
    }
    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Cấu trúc fill/mask/use/transform không thuộc tập hỗ trợ");
    }
}
