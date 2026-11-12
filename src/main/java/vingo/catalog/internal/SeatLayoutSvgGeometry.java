package vingo.catalog.internal;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import vingo.catalog.internal.SeatLayoutSvgParser.Bounds;

/** Hình học SVG v1, quy đổi về hệ tọa độ của viewBox. */
final class SeatLayoutSvgGeometry {
    private static final String NUMBER = "[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][-+]?[0-9]+)?";
    private static final Pattern TOKEN = Pattern.compile(NUMBER + "|[A-Za-z]");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,]*");
    private static final Pattern TRANSFORM = Pattern.compile("([A-Za-z]+)\\s*\\(([^()]*)\\)");
    private static final int MAX_DEPTH = 64;
    private final Map<String, Element> elements;

    SeatLayoutSvgGeometry(Map<String, Element> elements) {
        this.elements = elements;
    }

    Bounds bounds(Element zone) {
        try {
            Rectangle2D bounds = shapeBounds(zone, parentTransform(zone), 0);
            if (bounds == null || bounds.isEmpty()) {
                throw new IllegalArgumentException();
            }
            return new Bounds(BigDecimal.valueOf(bounds.getX()), BigDecimal.valueOf(bounds.getY()),
                    BigDecimal.valueOf(bounds.getWidth()), BigDecimal.valueOf(bounds.getHeight()));
        } catch (IllegalArgumentException ex) {
            throw SeatLayoutSvgParser.invalid(zone.getAttribute("id"), "INVALID_ZONE_GEOMETRY",
                    "Đường viền khu không có khung bao hợp lệ hoặc dùng lệnh SVG chưa hỗ trợ.");
        }
    }

    Point seatCenter(Element element) {
        try {
            BigDecimal x = decimal(element.getAttribute("cx"));
            BigDecimal y = decimal(element.getAttribute("cy"));
            if (decimal(element.getAttribute("r")).signum() <= 0) {
                throw new IllegalArgumentException();
            }
            AffineTransform transform = elementTransform(element, parentTransform(element));
            if (transform.isIdentity()) {
                return new Point(x, y);
            }
            Point2D point = transform.transform(new Point2D.Double(x.doubleValue(), y.doubleValue()), null);
            return new Point(BigDecimal.valueOf(point.getX()), BigDecimal.valueOf(point.getY()));
        } catch (IllegalArgumentException ex) {
            throw SeatLayoutSvgParser.invalid(element.getAttribute("id"), "INVALID_SEAT_GEOMETRY",
                    "Tọa độ, bán kính hoặc transform của ghế không hợp lệ.");
        }
    }

    static BigDecimal decimal(String value) {
        BigDecimal number = new BigDecimal(value.strip());
        if (number.scale() > 1_000 || !Double.isFinite(number.doubleValue())) {
            throw new NumberFormatException();
        }
        return number;
    }

    private Rectangle2D shapeBounds(Element element, AffineTransform parent, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException();
        }
        AffineTransform transform = elementTransform(element, parent);
        return switch (element.getLocalName()) {
            case "g" -> groupBounds(element, transform, depth);
            case "use" -> {
                transform.translate(number(element, "x", 0), number(element, "y", 0));
                String href = SeatLayoutSvgParser.reference(element);
                yield shapeBounds(elements.get(href.substring(1)), transform, depth + 1);
            }
            case "path" -> transform.createTransformedShape(path(element.getAttribute("d"))).getBounds2D();
            case "rect" -> transform.createTransformedShape(new Rectangle2D.Double(
                    number(element, "x", 0), number(element, "y", 0),
                    positive(element, "width"), positive(element, "height"))).getBounds2D();
            case "polygon", "polyline" -> transform.createTransformedShape(points(element)).getBounds2D();
            case "circle" -> {
                double radius = positive(element, "r");
                double x = number(element, "cx", 0);
                double y = number(element, "cy", 0);
                yield transform.createTransformedShape(new Rectangle2D.Double(
                        x - radius, y - radius, 2 * radius, 2 * radius)).getBounds2D();
            }
            case "line" -> transform.createTransformedShape(new java.awt.geom.Line2D.Double(
                    number(element, "x1", 0), number(element, "y1", 0),
                    number(element, "x2", 0), number(element, "y2", 0))).getBounds2D();
            case "mask", "defs" -> null;
            default -> throw new IllegalArgumentException();
        };
    }

    private Rectangle2D groupBounds(Element group, AffineTransform transform, int depth) {
        Rectangle2D union = null;
        for (Node node = group.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                Rectangle2D bounds = shapeBounds(child, transform, depth + 1);
                if (bounds != null) {
                    union = union == null ? bounds : union.createUnion(bounds);
                }
            }
        }
        return union;
    }

    private static AffineTransform parentTransform(Element element) {
        List<Element> parents = new ArrayList<>();
        for (Node node = element.getParentNode(); node instanceof Element parent; node = parent.getParentNode()) {
            if (parents.size() >= MAX_DEPTH) {
                throw new IllegalArgumentException();
            }
            parents.add(parent);
        }
        AffineTransform transform = new AffineTransform();
        for (Element parent : parents.reversed()) {
            transform.concatenate(readTransform(parent.getAttribute("transform")));
        }
        return transform;
    }

    private static AffineTransform elementTransform(Element element, AffineTransform parent) {
        AffineTransform result = new AffineTransform(parent);
        result.concatenate(readTransform(element.getAttribute("transform")));
        return result;
    }

    private static AffineTransform readTransform(String source) {
        AffineTransform result = new AffineTransform();
        var matcher = TRANSFORM.matcher(source);
        int end = 0;
        while (matcher.find()) {
            requireSeparator(source.substring(end, matcher.start()));
            double[] args = tokens(matcher.group(2)).stream().mapToDouble(SeatLayoutSvgGeometry::readNumber).toArray();
            result.concatenate(switch (matcher.group(1)) {
                case "matrix" -> {
                    requireLength(args, 6, 6);
                    yield new AffineTransform(args);
                }
                case "translate" -> {
                    requireLength(args, 1, 2);
                    yield AffineTransform.getTranslateInstance(args[0], args.length == 2 ? args[1] : 0);
                }
                case "scale" -> {
                    requireLength(args, 1, 2);
                    yield AffineTransform.getScaleInstance(args[0], args.length == 2 ? args[1] : args[0]);
                }
                case "rotate" -> {
                    if (args.length == 1) {
                        yield AffineTransform.getRotateInstance(Math.toRadians(args[0]));
                    }
                    requireLength(args, 3, 3);
                    yield AffineTransform.getRotateInstance(Math.toRadians(args[0]), args[1], args[2]);
                }
                case "skewX", "skewY" -> {
                    requireLength(args, 1, 1);
                    double shear = Math.tan(Math.toRadians(args[0]));
                    yield "skewX".equals(matcher.group(1))
                            ? AffineTransform.getShearInstance(shear, 0)
                            : AffineTransform.getShearInstance(0, shear);
                }
                default -> throw new IllegalArgumentException();
            });
            end = matcher.end();
        }
        requireSeparator(source.substring(end));
        return result;
    }

    private static void requireLength(double[] args, int min, int max) {
        if (args.length < min || args.length > max) {
            throw new IllegalArgumentException();
        }
    }

    private static Path2D points(Element element) {
        List<String> values = tokens(element.getAttribute("points"));
        if (values.size() < 4 || values.size() % 2 != 0) {
            throw new IllegalArgumentException();
        }
        var shape = new Path2D.Double();
        shape.moveTo(readNumber(values.get(0)), readNumber(values.get(1)));
        for (int index = 2; index < values.size(); index += 2) {
            shape.lineTo(readNumber(values.get(index)), readNumber(values.get(index + 1)));
        }
        if ("polygon".equals(element.getLocalName())) {
            shape.closePath();
        }
        return shape;
    }

    private static Path2D path(String source) {
        List<String> values = tokens(source);
        var shape = new Path2D.Double();
        char command = 0;
        int index = 0;
        while (index < values.size()) {
            String token = values.get(index);
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                index++;
                if (command == 'Z' || command == 'z') {
                    if (shape.getCurrentPoint() == null) {
                        throw new IllegalArgumentException();
                    }
                    shape.closePath();
                    command = 0;
                    continue;
                }
            }
            char kind = Character.toUpperCase(command);
            int count = switch (kind) {
                case 'H', 'V' -> 1;
                case 'M', 'L' -> 2;
                case 'Q' -> 4;
                case 'C' -> 6;
                default -> throw new IllegalArgumentException();
            };
            Point2D current = shape.getCurrentPoint();
            if (index + count > values.size() || (current == null && kind != 'M')) {
                throw new IllegalArgumentException();
            }
            double[] args = new double[count];
            for (int offset = 0; offset < count; offset++) {
                args[offset] = readNumber(values.get(index++));
                if (current != null && Character.isLowerCase(command)) {
                    args[offset] += kind == 'V' || offset % 2 == 1 ? current.getY() : current.getX();
                }
            }
            switch (kind) {
                case 'M' -> {
                    shape.moveTo(args[0], args[1]);
                    command = command == 'm' ? 'l' : 'L';
                }
                case 'L' -> shape.lineTo(args[0], args[1]);
                case 'H' -> shape.lineTo(args[0], current.getY());
                case 'V' -> shape.lineTo(current.getX(), args[0]);
                case 'Q' -> shape.quadTo(args[0], args[1], args[2], args[3]);
                case 'C' -> shape.curveTo(args[0], args[1], args[2], args[3], args[4], args[5]);
                default -> throw new IllegalStateException();
            }
        }
        return shape;
    }

    private static List<String> tokens(String source) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(source);
        int end = 0;
        while (matcher.find()) {
            requireSeparator(source.substring(end, matcher.start()));
            tokens.add(matcher.group());
            end = matcher.end();
        }
        requireSeparator(source.substring(end));
        return tokens;
    }

    private static void requireSeparator(String value) {
        if (!SEPARATOR.matcher(value).matches()) {
            throw new IllegalArgumentException();
        }
    }

    private static double readNumber(String value) {
        return decimal(value).doubleValue();
    }

    private static double number(Element element, String attribute, double fallback) {
        return element.hasAttribute(attribute) ? readNumber(element.getAttribute(attribute)) : fallback;
    }

    private static double positive(Element element, String attribute) {
        double value = readNumber(element.getAttribute(attribute));
        if (value <= 0) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    record Point(BigDecimal x, BigDecimal y) {}
}
