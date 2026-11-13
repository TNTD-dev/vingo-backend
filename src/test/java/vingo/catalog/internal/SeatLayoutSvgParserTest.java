package vingo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

class SeatLayoutSvgParserTest {

    private final SeatLayoutSvgParser parser = new SeatLayoutSvgParser();

    @Test
    void parseViewBox_readsThienTruongSvg() throws IOException {
        byte[] input = Files.readAllBytes(
                Path.of("sources", "LAYOUT__THIEN-TRUONG.svg")
        );

        var bounds = parser.parseViewBox(input);

        assertThat(bounds.x()).isEqualByComparingTo("0");
        assertThat(bounds.y()).isEqualByComparingTo("0");
        assertThat(bounds.width()).isEqualByComparingTo("1600");
        assertThat(bounds.height()).isEqualByComparingTo("1200");
    }

    @Test
    void parse_readsZonesSeatsAndLocalReferencesFromThienTruong() throws IOException {
        var parsed = parser.parse(Files.readAllBytes(Path.of("sources", "LAYOUT__THIEN-TRUONG.svg")));

        assertThat(parsed.zones()).hasSize(27);
        assertThat(parsed.zones().stream().mapToInt(zone -> zone.seats().size()).sum()).isEqualTo(6699);
        assertThat(parsed.zones().stream()
                .filter(zone -> zone.kind() == SeatLayoutSvgParser.ZoneKind.STANDING)
                .mapToInt(SeatLayoutSvgParser.ParsedZone::capacity).sum()).isEqualTo(9000);
        var zone = parsed.zones().stream().filter(value -> value.code().equals("B3-T1")).findFirst().orElseThrow();
        assertThat(zone.bounds().width()).isPositive();
        var seat = zone.seats().stream().filter(value -> value.rowLabel().equals("A") && value.number() == 1)
                .findFirst().orElseThrow();
        assertThat(seat.x()).isEqualByComparingTo("532.77");
        assertThat(seat.y()).isEqualByComparingTo("349.88");
        assertThat(parsed.svgContent()).contains("#stroke0_11_8742");
    }

    @Test
    void parseViewBox_preservesNegativeOriginAndDecimalDimensions() {
        var bounds = parser.parseViewBox(svg("-12.5 -8.25 100.5 80.25"));

        assertThat(bounds.x()).isEqualByComparingTo("-12.5");
        assertThat(bounds.y()).isEqualByComparingTo("-8.25");
        assertThat(bounds.width()).isEqualByComparingTo("100.5");
        assertThat(bounds.height()).isEqualByComparingTo("80.25");
    }

    @Test
    void parseViewBox_rejectsZeroWidth() {
        assertInvalid(svg("0 0 0 1200"), "INVALID_VIEW_BOX");
    }

    @Test
    void parseViewBox_rejectsMissingViewBoxValue() {
        assertInvalid(svg("0 0 1600"), "INVALID_VIEW_BOX");
    }

    @Test
    void parseViewBox_rejectsWrongRootElement() {
        assertInvalid(bytes("<layout/>"), "INVALID_SVG_ROOT");
    }

    @Test
    void parseViewBox_rejectsMalformedXml() {
        assertInvalid(
                bytes("<svg xmlns=\"http://www.w3.org/2000/svg\">"),
                "MALFORMED_XML"
        );
    }

    private void assertInvalid(byte[] input, String reason) {
        DomainException error = assertThrows(
                DomainException.class,
                () -> parser.parseViewBox(input)
        );

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_SEAT_LAYOUT);

        List<?> issues = assertInstanceOf(List.class, error.properties().get("issues"));
        assertThat(issues).hasSize(1);

        Map<?, ?> issue = assertInstanceOf(Map.class, issues.getFirst());
        assertThat(issue.get("reason")).isEqualTo(reason);
        assertThat(assertInstanceOf(String.class, issue.get("message"))).isNotBlank();
    }

    @Test
    void parse_rejectsZoneOutsideViewBoxAfterTransform() {
        DomainException error = assertThrows(DomainException.class, () -> parser.parse(bytes("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="-10 -10 100 100">
                  <g transform="translate(200 0)">
                    <rect id="ZONE__A-T1__STANDING__10" width="20" height="20"/>
                  </g>
                </svg>
                """)));
        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_SEAT_LAYOUT);
        List<?> issues = assertInstanceOf(List.class, error.properties().get("issues"));
        Map<?, ?> issue = assertInstanceOf(Map.class, issues.getFirst());
        assertThat(issue.get("reason")).isEqualTo("ZONE_OUTSIDE_VIEW_BOX");
        assertThat(issue.get("layer_id")).isEqualTo("ZONE__A-T1__STANDING__10");
    }

    @Test
    void parse_acceptsZonePartiallyInsideViewBox() {
        var parsed = parser.parse(bytes("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="-10 -10 100 100">
                  <rect id="ZONE__A-T1__STANDING__10" x="-20" y="-20" width="20" height="20"/>
                </svg>
                """));
        assertThat(parsed.zones()).hasSize(1);
        assertThat(parsed.zones().getFirst().capacity()).isEqualTo(10);
    }

    @Test
    void parse_rejectsZoneOnlyTouchingViewBoxEdge() {
        assertParseInvalid("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                  <rect id="ZONE__A-T1__STANDING__10" x="100" width="20" height="20"/>
                </svg>
                """, "ZONE_OUTSIDE_VIEW_BOX");
    }

    @ParameterizedTest
    @MethodSource("unsafeSvg")
    void parse_rejectsUnsafeSvg(String input, String reason) {
        assertParseInvalid(input, reason);
    }

    static Stream<Arguments> unsafeSvg() {
        String header = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>";
        return Stream.of(
                Arguments.of("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///unused'>]>" + header + "&x;</svg>", "MALFORMED_XML"),
                Arguments.of("<?xml-stylesheet href='https://example.com/a.css'?>" + header + "</svg>", "UNSUPPORTED_SVG_ELEMENT"),
                Arguments.of(header + "<script/></svg>", "UNSUPPORTED_SVG_ELEMENT"),
                Arguments.of(header + "<foreignObject/></svg>", "UNSUPPORTED_SVG_ELEMENT"),
                Arguments.of(header.replace(">", " onload='alert(1)'>") + "</svg>", "UNSUPPORTED_SVG_ATTRIBUTE"),
                Arguments.of(header.replace(">", " style='fill:red'>") + "</svg>", "UNSUPPORTED_SVG_ATTRIBUTE"),
                Arguments.of(header + "<rect fill='url(https://example.com/a)'/></svg>", "INVALID_PAINT"),
                Arguments.of(header + "<use href='https://example.com/a.svg#x'/></svg>", "INVALID_SVG_REFERENCE"));
    }

    @ParameterizedTest
    @MethodSource("invalidSeats")
    void parse_rejectsInvalidSeatStructure(String content, String reason) {
        assertParseInvalid("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                  <rect id="ZONE__A-T1__SEATED" width="50" height="50"/>
                  %s
                </svg>
                """.formatted(content), reason);
    }

    static Stream<Arguments> invalidSeats() {
        String seat = "<circle id='SEAT__A-T1__A__1' cx='10' cy='10' r='1'/>";
        String pitch = "<path id='PITCH_SIDE__A-T1' d='M0 0 L50 0'/>";
        return Stream.of(
                Arguments.of("<g id='CONTENT__A-T1'>" + seat
                        + seat.replace("__1'", "__01'") + pitch + "</g>", "DUPLICATE_SEAT"),
                Arguments.of("<g id='CONTENT__A-T1'>" + seat.replace("cx='10'", "cx='70'")
                        + pitch + "</g>", "SEAT_OUTSIDE_ZONE"),
                Arguments.of(seat, "INVALID_SEAT_ZONE"),
                Arguments.of("<g id='CONTENT__A-T1'>" + seat + "</g>", "MISSING_ZONE_DETAIL"));
    }

    private void assertParseInvalid(String input, String reason) {
        DomainException error = assertThrows(DomainException.class, () -> parser.parse(bytes(input)));
        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_SEAT_LAYOUT);
        List<?> issues = assertInstanceOf(List.class, error.properties().get("issues"));
        assertThat(issues).hasSize(1);
        Map<?, ?> issue = assertInstanceOf(Map.class, issues.getFirst());
        assertThat(issue.get("reason")).isEqualTo(reason);
    }

    private static byte[] svg(String viewBox) {
        return bytes("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="%s"/>
                """.formatted(viewBox));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
