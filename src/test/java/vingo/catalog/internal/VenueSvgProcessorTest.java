package vingo.catalog.internal;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vingo.shared.error.DomainException;
import vingo.support.VenueImportHttpFixture;

class VenueSvgProcessorTest {
    private final VenueSvgProcessor processor=new VenueSvgProcessor(new SeatLayoutSvgParser());
    static String svg(String content) {return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>"+content+"</svg>";}
    static String base() {return svg("<rect id='GATE__G1__IN' x='90' y='90' width='5' height='5'/>");}
    static String detail(String code) {return svg("<rect id='ZONE__"+code+"__STANDING__10' width='20' height='20'/>");}
    @Test void identifiesBaseWithoutSeatsAndPreservesDirection() {
        assertThat(processor.inspect(base().getBytes(StandardCharsets.UTF_8)).valid()).isTrue();
        var bundle=processor.combine(base(),List.of(detail("A")),Map.of());
        assertThat(bundle.gates().getFirst().direction()).isEqualTo("IN");assertThat(bundle.layout().schemaVersion()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"<script/>","<rect onclick='x()'/>","<use href='https://evil.test/a.svg'/>","<image href='x'/>","<rect style='fill:red'/>","<?bad x?>"})
    void unsafeFilesHaveNoPreview(String unsafe) {
        var result=processor.inspect(svg(unsafe).getBytes(StandardCharsets.UTF_8));assertThat(result.valid()).isFalse();assertThat(result.safeSvg()).isNull();
    }
    @Test void rejectsLegacyCombinedWithoutGuessingSplit() {
        var source=processor.inspect(svg("<rect id='GATE__G1' x='90' y='90' width='2' height='2'/><rect id='ZONE__A__STANDING__10' width='20' height='20'/>").getBytes(StandardCharsets.UTF_8));
        assertThat(source.role()).isEqualTo("COMBINED_V1");assertThat(source.issues().getFirst().get("reason")).isEqualTo("COMBINED_REQUIRES_SPLIT");
    }
    @Test void rejectsViewBoxMismatchAndDuplicateZoneAcrossFiles() {
        reason(()->processor.combine(base(),List.of(detail("A").replace("100 100","200 100")),Map.of()),"VIEW_BOX_MISMATCH");
        reason(()->processor.combine(base(),List.of(detail("A"),detail("A")),Map.of()),"DUPLICATE_ID");
    }
    @Test void namespacesLocalDefinitionsAndResolvesUse() {
        String a=svg("<defs><path id='shape' d='M0 0H20V20H0Z'/></defs><g id='ZONE__A__STANDING__10'><use href='#shape'/></g>");
        String b=svg("<defs><path id='shape' d='M30 0H50V20H30Z'/></defs><g id='ZONE__B__STANDING__10'><use href='#shape'/></g>");
        var bundle=processor.combine(base(),List.of(a,b),Map.of());assertThat(bundle.layout().zones()).hasSize(2);
        assertThat(bundle.layout().svgContent()).contains("FILE_0__shape","FILE_1__shape","#FILE_1__shape");
    }
    @ParameterizedTest
    @ValueSource(strings = {"#fff0", "#ffffff00"})
    void transparentDecorationDoesNotOverlapGate(String fill) {
        String layout = svg("<g id='ZONE__A__STANDING__10'><rect width='20' height='20'/>"
                + "<rect x='90' y='90' width='5' height='5' fill='" + fill + "'/></g>");
        assertThat(processor.combine(base(), List.of(layout), Map.of()).layout().zones()).hasSize(1);
    }

    @Test void preservesHexColorsWhenRenamingReferencedIds() {
        String layout = svg("<defs><path id='fff' d='M0 0H20V20H0Z'/></defs>"
                + "<g id='ZONE__A__STANDING__10' fill='#fff' stroke='#fff'><use href='#fff'/></g>");
        assertThat(processor.combine(base(), List.of(layout), Map.of()).layout().svgContent())
                .contains("fill=\"#fff\"", "stroke=\"#fff\"", "href=\"#FILE_0__fff\"");
    }

    @Test void generatedTechnicalIdsCannotCollideWithIdsInOverview() {
        String overview=base().replace("</svg>","<defs><rect id='FILE_0__shape' width='1' height='1'/></defs></svg>");
        String detail=svg("<defs><path id='shape' d='M0 0H20V20H0Z'/></defs><g id='ZONE__A__STANDING__10'><use href='#shape'/></g>");
        assertThat(processor.combine(overview,List.of(detail),Map.of()).layout().svgContent()).contains("V2_FILE_0__shape","#V2_FILE_0__shape");
    }
    @Test void mapsActualShapeAndRequiresUnambiguousArea() {
        String base=svg("<rect id='GATE__G1' x='90' y='90' width='2' height='2'/><rect id='AREA__A' width='50' height='50'/><rect id='AREA__B' width='60' height='60'/>");
        reason(()->processor.combine(base,List.of(detail("A")),Map.of()),"INVALID_AREA_MAPPING");
        assertThat(processor.combine(base,List.of(detail("A")),Map.of("A","B")).zoneAreas()).containsEntry("A","B");
        reason(()->processor.combine(base,List.of(detail("A")),Map.of("A","UNKNOWN")),"INVALID_AREA_MAPPING");
    }
    @Test void rejectsOverlappingZonesAndSeatFootprintOutsideZone() {
        reason(()->processor.combine(base(),List.of(detail("A"),detail("B")),Map.of()),"ZONE_OVERLAPS_ZONE");
        String detail=svg("<rect id='ZONE__A__SEATED' width='20' height='20'/><g id='CONTENT__A'><circle id='SEAT__A__R__1' cx='19.9' cy='5' r='1'/><path id='PITCH_SIDE__A' d='M0 0H20'/></g>");
        reason(()->processor.combine(base(),List.of(detail),Map.of()),"SEAT_OUTSIDE_ZONE");
    }
    @Test void acceptsSmallSeatOverflowWithoutChangingSeatCoordinatesOrCount() {
        var bundle = processor.combine(base(), List.of(seatedDetail("19.02", "1")), Map.of());
        assertThat(bundle.layout().zones().getFirst().seats()).singleElement()
                .satisfies(seat -> assertThat(seat.x()).isEqualByComparingTo("19.02"));
    }
    @Test void includesEveryPathOfACompoundZone() {
        String detail = svg("<g id='ZONE__A__SEATED'>"
                + "<path d='M0 0H8V20H0Z'/><path d='M12 0H20V20H12Z'/></g>"
                + "<g id='CONTENT__A'><circle id='SEAT__A__R__1' cx='15' cy='10' r='1'/>"
                + "<path id='PITCH_SIDE__A' d='M0 0H20'/></g>");
        assertThat(processor.combine(base(), List.of(detail), Map.of()).layout().zones().getFirst().seats())
                .singleElement().satisfies(seat -> assertThat(seat.x()).isEqualByComparingTo("15"));
    }
    @Test void acceptsFootballSeatAtRoundingSeamBetweenZonePaths() {
        String detail = svg("<g id='ZONE__A-T5-Z02__SEATED'>"
                + "<path d='M897.295 1782.58L887.437 1780.03L874.439 1776.66L868.68 1775.05"
                + "L850.442 1769.96L850.14 1769.87L831.797 1764.39L826.644 1762.86L813.557 1758.7"
                + "L803.047 1755.36L797.002 1753.29L878.486 1551.93L953.625 1582.33L897.295 1782.58Z'/>"
                + "<path d='M940.434 1679.46L910.535 1785.76L906.155 1784.72L898.736 1782.95"
                + "L897.295 1782.58L927.34 1675.78L940.434 1679.46Z'/></g>"
                + "<g id='CONTENT__A-T5-Z02'><circle id='SEAT__A-T5-Z02__R28__23' cx='922.917' cy='1691.5' r='1.75'/>"
                + "<path id='PITCH_SIDE__A-T5-Z02' d='M897.295 1782.58L953.625 1582.33'/></g>")
                .replace("100 100", "2500 2000");
        var bundle = processor.combine(base().replace("100 100", "2500 2000"), List.of(detail), Map.of());
        assertThat(bundle.layout().zones().getFirst().seats()).singleElement().satisfies(seat -> {
            assertThat(seat.svgId()).isEqualTo("SEAT__A-T5-Z02__R28__23");
            assertThat(seat.x()).isEqualByComparingTo("922.917");
            assertThat(seat.y()).isEqualByComparingTo("1691.5");
        });
    }
    @Test void preservesZoneCutoutWiderThanRoundingJoinEvenBelowAreaLimit() {
        String detail = svg("<g id='ZONE__A__SEATED'>"
                + "<path d='M0 0H9.994V20H0Z'/><path d='M10.006 0H20V20H10.006Z'/></g>"
                + "<g id='CONTENT__A'><circle id='SEAT__A__R__1' cx='10' cy='10' r='1'/>"
                + "<path id='PITCH_SIDE__A' d='M0 0H20'/></g>");
        DomainException ex = catchThrowableOfType(DomainException.class,
                () -> processor.combine(base(), List.of(detail), Map.of()));
        assertThat(ex).isNotNull();
        var issue = VenueSvgProcessor.issues(ex).getFirst();
        assertThat(issue).containsEntry("reason", "SEAT_OUTSIDE_ZONE");
        assertThat(issue.get("message").toString()).contains("Tâm ghế");
        assertThat(((Number) issue.get("intersection_area")).doubleValue())
                .isLessThan(((Number) issue.get("tolerance")).doubleValue());
    }
    @Test void rejectsSeatOverflowAboveAbsoluteLimitEvenWhenBelowOnePercent() {
        assertSeatOverflow(seatedDetail("10.04", "10"));
    }
    @Test void rejectsSeatOverflowAboveRelativeLimitEvenWhenBelowAbsoluteLimit() {
        assertSeatOverflow(seatedDetail("19.92", "0.1"));
    }
    private void assertSeatOverflow(String detail) {
        DomainException ex = catchThrowableOfType(DomainException.class,
                () -> processor.combine(base(), List.of(detail), Map.of()));
        assertThat(ex).isNotNull();
        var issue = VenueSvgProcessor.issues(ex).getFirst();
        assertThat(issue).containsEntry("reason", "SEAT_OUTSIDE_ZONE")
                .containsEntry("layer_id", "SEAT__A__R__1").containsEntry("zone_id", "ZONE__A__SEATED");
        assertThat(((Number) issue.get("intersection_area")).doubleValue())
                .isGreaterThan(((Number) issue.get("tolerance")).doubleValue());
    }
    private static String seatedDetail(String x, String radius) {
        return svg("<rect id='ZONE__A__SEATED' width='20' height='20'/>"
                + "<g id='CONTENT__A'><circle id='SEAT__A__R__1' cx='" + x + "' cy='10' r='" + radius + "'/>"
                + "<path id='PITCH_SIDE__A' d='M0 0H20'/></g>");
    }
    @Test void inspectsActualThienTruongAndMeasuresSplitInsteadOfAssumingValidity() throws Exception {
        String svg=Files.readString(Path.of("sources/LAYOUT__THIEN-TRUONG.svg"));
        assertThat(processor.inspect(svg.getBytes(StandardCharsets.UTF_8)).role()).isEqualTo("COMBINED_V1");
        var layers=VenueImportHttpFixture.split(svg);
        DomainException ex=catchThrowableOfType(DomainException.class,()->processor.combine(layers[0],List.of(layers[1]),Map.of()));
        assertThat(ex.properties().get("issues").toString()).contains("ZONE_OVERLAPS_ZONE","ZONE__A6-T1__SEATED","C5-T1");
    }
    private static void reason(Runnable r,String reason) {
        DomainException ex=catchThrowableOfType(DomainException.class,r::run);
        assertThat(ex).isNotNull();assertThat(ex.properties().get("issues").toString()).contains(reason);
    }
}
