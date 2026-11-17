package vingo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import vingo.shared.error.DomainException;

class SeatLayoutCollisionTest {
    @Test
    void cutout_avoidsFalsePositiveAlthoughBoundsIntersect() throws Exception {
        var elements = elements("""
                <path id="ZONE__A__STANDING__10" fill-rule="evenodd" d="M0 0H10V10H0Z M2 2H8V8H2Z"/>
                <rect id="GATE__G1" x="3" y="3" width="2" height="2"/>
                """);
        var geometry = new SeatLayoutSvgGeometry(elements);
        var zone = geometry.area(elements.get("ZONE__A__STANDING__10"));
        var gate = geometry.area(elements.get("GATE__G1"));
        assertThat(zone.getBounds2D().intersects(gate.getBounds2D())).isTrue();
        assertThat(SeatLayoutCollisionValidator.intersection(zone, gate).isEmpty()).isTrue();
        assertThat(SeatLayoutAreaMeasure.area(zone)).isEqualTo(64);
        SeatLayoutCollisionValidator.validate(elements, geometry);
        assertThat(SeatLayoutAreaMeasure.area(zone)).isEqualTo(64);
    }

    @ParameterizedTest
    @MethodSource("windingCases")
    void winding_respectsInheritedRulesAndSubpathDirection(String rule, String inner, double expected) throws Exception {
        var elements = elements("<g fill-rule='" + rule + "'><path id='shape' d='M0 0H10V10H0Z " + inner + "'/></g>");
        var geometry = new SeatLayoutSvgGeometry(elements);
        assertThat(SeatLayoutAreaMeasure.area(geometry.area(elements.get("shape")))).isEqualTo(expected);
    }

    static Stream<Arguments> windingCases() {
        return Stream.of(Arguments.of("evenodd", "M2 2H8V8H2Z", 64),
                Arguments.of("nonzero", "M2 2H8V8H2Z", 100),
                Arguments.of("nonzero", "M2 2V8H8V2Z", 64));
    }

    @Test
    void use_resolvesTargetWindingAndInheritedFillInsteadOfDefsParentStyle() throws Exception {
        var elements = elements("""
                <defs fill="none"><path id="source" fill-rule="evenodd" d="M0 0H10V10H0Z M2 2H8V8H2Z"/></defs>
                <g fill="none" transform="translate(10 20)"><use id="shape" href="#source" fill="red" x="2"/></g>
                """);
        var shape = new SeatLayoutSvgGeometry(elements).area(elements.get("shape"));
        assertThat(SeatLayoutAreaMeasure.area(shape)).isEqualTo(64);
        assertThat(shape.getBounds2D().getX()).isEqualTo(12);
        assertThat(shape.getBounds2D().getY()).isEqualTo(20);
        assertThat(shape.contains(15, 23)).isFalse();
    }

    @Test
    void mask_appliesBinaryWhiteBlackGeometryAndIgnoresDecorativeStroke() throws Exception {
        var elements = elements("""
                <defs><mask id="m" fill="white"><rect width="10" height="10"/>
                    <rect x="2" y="2" width="6" height="6" fill="black"/></mask></defs>
                <g id="shape" fill="none"><rect width="10" height="10" fill="red" mask="url(#m)"/>
                    <path d="M0 0H10V10H0Z" stroke="black" stroke-width="20"/>
                    <rect id="GATE_LABEL__G1" x="2" y="2" width="6" height="6" fill="red"/>
                </g>
                """);
        var shape = new SeatLayoutSvgGeometry(elements).area(elements.get("shape"));
        assertThat(SeatLayoutAreaMeasure.area(shape)).isEqualTo(64);
        assertThat(shape.contains(3, 3)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("unsupportedCases")
    void unsupportedGeometry_failsExplicitly(String source) throws Exception {
        var elements = elements(source);
        var error = assertThrows(DomainException.class, () -> new SeatLayoutSvgGeometry(elements).area(elements.get("shape")));
        assertThat(issue(error).get("reason")).isEqualTo("UNSUPPORTED_COLLISION_GEOMETRY");
    }

    static Stream<String> unsupportedCases() {
        return Stream.of(
                "<path id='shape' d='M0 0 A10 10 0 0 0 20 20 Z'/>",
                "<defs><mask id='m'><rect width='10' height='10' fill='gray'/></mask></defs><rect id='shape' width='10' height='10' mask='url(#m)'/>",
                "<defs><mask id='m' opacity='.5'><rect width='10' height='10' fill='white'/></mask></defs><rect id='shape' width='10' height='10' mask='url(#m)'/>",
                "<defs><mask id='m' maskContentUnits='objectBoundingBox'><rect width='1' height='1' fill='white'/></mask></defs><rect id='shape' width='10' height='10' mask='url(#m)'/>",
                "<defs><mask id='m'><g fill='white'><rect width='10' height='10'/></g></mask></defs><rect id='shape' width='10' height='10' mask='url(#m)'/>",
                "<defs><mask id='m' fill='white' mask-type='alpha'><rect width='10' height='10'/></mask></defs><rect id='shape' width='10' height='10' mask='url(#m)'/>",
                "<defs><mask id='m' fill='white' transform='translate(1 1)'><rect width='10' height='10'/></mask></defs><rect id='shape' width='10' height='10' mask='url(#m)'/>",
                "<defs><mask id='m' fill='white'><rect width='10' height='10'/></mask></defs><g id='shape' mask='url(#m)'><rect width='10' height='10'/></g>",
                "<use id='shape' href='#shape'/>",
                "<rect id='shape' width='10' height='10' transform='translate(NaN 0)'/>");
    }

    @Test
    void transformedSeat_usesEllipseNotOnlyCenterOrAxisAlignedCircle() throws Exception {
        var elements = elements("<g transform='translate(20 30) rotate(90)'><circle id='seat' cx='0' cy='0' r='2' transform='scale(2 .5)'/></g>");
        var shape = new SeatLayoutSvgGeometry(elements).seatArea(elements.get("seat"));
        assertThat(shape.getBounds2D().getWidth()).isCloseTo(2, within(1e-10));
        assertThat(shape.getBounds2D().getHeight()).isCloseTo(8, within(1e-10));
        assertThat(shape.contains(20, 33)).isTrue();
        assertThat(shape.contains(22, 30)).isFalse();
        assertThat(SeatLayoutAreaMeasure.area(shape)).isCloseTo(4 * Math.PI, within(.004));
    }

    @Test
    void mask_userSpaceRegionClipsAndReferencedBlackSubtracts() throws Exception {
        var elements = elements("""
                <defs><rect id="black" x="2" y="2" width="2" height="2" fill="black"/>
                <mask id="m" fill="white" maskUnits="userSpaceOnUse" x="0" y="0" width="5" height="5">
                    <rect width="10" height="10"/><use href="#black"/>
                </mask></defs>
                <rect id="shape" width="10" height="10" mask="url(#m)"/>
                """);
        var shape = new SeatLayoutSvgGeometry(elements).area(elements.get("shape"));
        assertThat(SeatLayoutAreaMeasure.area(shape)).isEqualTo(21);
        assertThat(shape.contains(3, 3)).isFalse();
        assertThat(shape.contains(6, 1)).isFalse();
    }

    @Test
    void seatArea_rejectsSingularTransformsAndMaskedSeats() throws Exception {
        var elements = elements("<circle id='seat' cx='1' cy='1' r='1' transform='scale(0 1)'/>");
        var error = assertThrows(DomainException.class, () -> new SeatLayoutSvgGeometry(elements).seatArea(elements.get("seat")));
        assertThat(issue(error).get("reason")).isEqualTo("UNSUPPORTED_COLLISION_GEOMETRY");
    }

    @ParameterizedTest
    @MethodSource("seatCases")
    void seatCollision_checksFullShapeAndAllowsZeroAreaTouch(double center, boolean collision) throws Exception {
        var elements = elements("<circle id='SEAT__A__R__1' cx='" + center + "' cy='5' r='1'/>"
                + "<rect id='GATE__G1' x='4' y='4' width='2' height='2'/>");
        if (collision) {
            var error = assertThrows(DomainException.class, () -> SeatLayoutCollisionValidator.validate(elements, new SeatLayoutSvgGeometry(elements)));
            assertThat(issue(error)).containsEntry("reason", "SEAT_OVERLAPS_GATE")
                    .containsEntry("gate_id", "GATE__G1").containsEntry("zone_id", "ZONE__A__SEATED")
                    .containsEntry("layer_id", "SEAT__A__R__1").containsEntry("tolerance", 0.0);
            assertThat(((Number) issue(error).get("intersection_area")).doubleValue()).isPositive();
        } else SeatLayoutCollisionValidator.validate(elements, new SeatLayoutSvgGeometry(elements));
    }

    static Stream<Arguments> seatCases() {
        return Stream.of(Arguments.of(3.5, true), Arguments.of(5, true), Arguments.of(3, false), Arguments.of(2, false));
    }

    @ParameterizedTest
    @MethodSource("zoneCases")
    void zoneCollision_appliesAreaThreshold(double gateX, boolean collision) throws Exception {
        var elements = elements("<rect id='ZONE__A__STANDING__10' width='1' height='1'/>"
                + "<rect id='GATE__G1' x='" + gateX + "' width='1' height='1'/>");
        if (collision) {
            var error = assertThrows(DomainException.class, () -> SeatLayoutCollisionValidator.validate(elements, new SeatLayoutSvgGeometry(elements)));
            assertThat(issue(error)).containsEntry("reason", "ZONE_OVERLAPS_GATE")
                    .containsEntry("gate_id", "GATE__G1").containsEntry("tolerance", .5);
            assertThat(((Number) issue(error).get("intersection_area")).doubleValue()).isGreaterThan(.5);
        } else SeatLayoutCollisionValidator.validate(elements, new SeatLayoutSvgGeometry(elements));
    }

    static Stream<Arguments> zoneCases() {
        return Stream.of(Arguments.of(2, false), Arguments.of(1, false), Arguments.of(.51, false),
                Arguments.of(.5, false), Arguments.of(.49, true), Arguments.of(.499999, true));
    }

    @ParameterizedTest
    @MethodSource("curveCases")
    void measure_integratesCurvesWithoutFlattening(String d, double expected) throws Exception {
        var elements = elements("<path id='shape' d='" + d + "' transform='translate(1000000 1000000)'/>");
        var area = new SeatLayoutSvgGeometry(elements).area(elements.get("shape"));
        assertThat(SeatLayoutAreaMeasure.area(area)).isCloseTo(expected, within(1e-8));
    }

    static Stream<Arguments> curveCases() {
        return Stream.of(Arguments.of("M0 0 Q1 1 2 0Z", 2.0 / 3),
                Arguments.of("M0 0 C0 1 1 1 1 0Z", .6),
                Arguments.of("M0 0 C0 1 1 1 1 0Z M.4 .1H.6V.2H.4Z", .58));
    }

    @ParameterizedTest
    @MethodSource("curveThresholdCases")
    void curvedIntersection_respectsThresholdWithoutFlattening(double height, boolean collision) throws Exception {
        var elements = elements("<g id='ZONE__A__STANDING__10' transform='scale(1 " + height + ")'>"
                + "<path d='M0 0C0 1 1 1 1 0Z'/></g><rect id='GATE__G1' width='2' height='2'/>");
        if (collision) {
            var error = assertThrows(DomainException.class, () -> SeatLayoutCollisionValidator.validate(elements, new SeatLayoutSvgGeometry(elements)));
            assertThat(issue(error).get("reason")).isEqualTo("ZONE_OVERLAPS_GATE");
        } else SeatLayoutCollisionValidator.validate(elements, new SeatLayoutSvgGeometry(elements));
    }

    static Stream<Arguments> curveThresholdCases() {
        return Stream.of(Arguments.of(.8, false), Arguments.of(5.0 / 6, false), Arguments.of(.84, true));
    }

    @Test
    void separateDocuments_validateTheirOwnReferencesAndMatchNumericViewBox() {
        var parser = new SeatLayoutSvgParser();
        byte[] detail = svg("<rect id='ZONE__A__STANDING__10' width='1' height='1'/>");
        byte[] overview = ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0.0 0 100.0 100'>"
                + "<rect id='GATE__G1' x='2' width='1' height='1'/></svg>").getBytes(StandardCharsets.UTF_8);
        parser.validateGateCollisions(overview, detail);
        var mismatch = assertThrows(DomainException.class, () -> parser.validateGateCollisions(overview,
                new String(detail, StandardCharsets.UTF_8).replace("100 100", "200 200").getBytes(StandardCharsets.UTF_8)));
        assertThat(issue(mismatch).get("reason")).isEqualTo("VIEW_BOX_MISMATCH");
        var overlap = assertThrows(DomainException.class, () -> parser.validateGateCollisions(
                new String(overview, StandardCharsets.UTF_8).replace("x='2'", "x='0'").getBytes(StandardCharsets.UTF_8), detail));
        assertThat(issue(overlap).get("reason")).isEqualTo("ZONE_OVERLAPS_GATE");
    }

    @Test
    void parser_rejectsUnsupportedMaskOnCombinedFile() {
        var error = assertThrows(DomainException.class, () -> new SeatLayoutSvgParser().parse(svg("""
                <defs><mask id="m"><rect width="5" height="5" fill="gray"/></mask></defs>
                <rect id="ZONE__A__STANDING__10" width="5" height="5" mask="url(#m)"/>
                <rect id="GATE__G1" x="10" width="2" height="2"/>
                """)));
        assertThat(issue(error).get("reason")).isEqualTo("UNSUPPORTED_COLLISION_GEOMETRY");
    }

    @Test
    void parser_returnsCollisionReasonAfterStructuralValidation() {
        var error = assertThrows(DomainException.class, () -> new SeatLayoutSvgParser().parse(svg("""
                <rect id="ZONE__A__SEATED" width="20" height="20"/>
                <g id="CONTENT__A"><circle id="SEAT__A__R__1" cx="3.5" cy="5" r="1"/>
                <path id="PITCH_SIDE__A" d="M0 0H20"/></g>
                <rect id="GATE__G1" x="4" y="4" width="2" height="2"/>
                """)));
        assertThat(issue(error).get("reason")).isEqualTo("SEAT_OVERLAPS_GATE");
    }

    @Test
    void thienTruong_resolvesActualUseMaskAndCutoutsAtBothGates() throws Exception {
        var elements = elements(Files.readAllBytes(Path.of("sources/LAYOUT__THIEN-TRUONG.svg")));
        var geometry = new SeatLayoutSvgGeometry(elements);
        var zone = geometry.area(elements.get("ZONE__B3-T1__SEATED"));
        for (String id : List.of("GATE__B3-T1__02", "GATE__B3-T1__03")) {
            var gate = geometry.area(elements.get(id));
            double intersection = SeatLayoutAreaMeasure.area(SeatLayoutCollisionValidator.intersection(zone, gate));
            assertThat(zone.getBounds2D().intersects(gate.getBounds2D())).as(id).isTrue();
            assertThat(intersection).as(id + " diện tích giao đo từ fixture thực").isLessThanOrEqualTo(.5);
        }
        try {
            SeatLayoutCollisionValidator.validate(elements, geometry);
        } catch (DomainException error) {
            throw new AssertionError("Collision thực trong fixture: " + error.properties(), error);
        }
    }

    private static Map<String, Object> issue(DomainException error) {
        @SuppressWarnings("unchecked")
        var issues = (List<Map<String, Object>>) error.properties().get("issues");
        return issues.getFirst();
    }

    private static Map<String, Element> elements(String content) throws Exception { return elements(svg(content)); }
    private static byte[] svg(String content) {
        return ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>" + content + "</svg>").getBytes(StandardCharsets.UTF_8);
    }
    private static Map<String, Element> elements(byte[] input) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(input)).getDocumentElement();
        var result = new LinkedHashMap<String, Element>();
        var nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (element.hasAttribute("id")) result.put(element.getAttribute("id"), element);
        }
        return result;
    }
}
