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

    private static byte[] svg(String viewBox) {
        return bytes("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="%s"/>
                """.formatted(viewBox));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
