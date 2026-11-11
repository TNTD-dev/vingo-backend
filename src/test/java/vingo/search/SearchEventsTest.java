package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.support.CatalogFixtures;
import vingo.support.IntegrationTest;

/** GET /events trên Postgres thật, dữ liệu dựng bằng {@link CatalogFixtures}, giờ cố định qua {@code MutableClock}. */
class SearchEventsTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ------------------------------------------------------------------ Khớp từ khoá

    @Test
    void searchEvents_findsEvent_whenQueryHasNoDiacritics() {
        UUID target = fixtures.event().title("Sơn Tùng M-TP Sky Tour").create().id();
        fixtures.event().title("Hòa nhạc Mùa Thu").create();

        assertThat(ids(search("q", "son tung"))).containsExactly(target);
        assertThat(ids(search("q", "SƠN TÙNG"))).containsExactly(target);
        assertThat(ids(search("q", "son tu"))).containsExactly(target); // gõ dở
    }

    @Test
    void searchEvents_findsEvent_whenQueryHasTypo() {
        UUID target = fixtures.event().title("Sơn Tùng M-TP Sky Tour").create().id();
        fixtures.event().title("Hòa nhạc Mùa Thu").create();

        assertThat(ids(search("q", "son tungg"))).containsExactly(target);
    }

    @Test
    void searchEvents_findsEvent_whenQueryIsPartOfLongTitle() {
        UUID target = fixtures.event()
                .title("Đêm nhạc đặc biệt chào mừng năm mới 2027 cùng nghệ sĩ Sơn Tùng M-TP Sky Tour")
                .create().id();
        fixtures.event().title("Hòa nhạc Mùa Thu").create();

        assertThat(ids(search("q", "sky tour"))).containsExactly(target);
        assertThat(ids(search("q", "chao mung nam moi"))).containsExactly(target);
    }

    @Test
    void searchEvents_returnsEmpty_whenQueryHasOnlySpecialCharacters() {
        fixtures.event().title("Sơn Tùng M-TP Sky Tour").create();

        assertThat(search("q", "!!! ...").path("data")).isEmpty();
    }

    // ------------------------------------------------------------------ Phạm vi kết quả

    @Test
    void searchEvents_excludesDraftAndPastEvents() {
        UUID visible = fixtures.event().title("Visible show").create().id();
        fixtures.event().title("Draft show").draft().create();
        fixtures.event().title("Cancelled show").status("CANCELLED").create();
        fixtures.event().title("Ended show").status("ENDED").create();
        fixtures.event().title("Past show")
                .showtime(s -> s.startsAt(NOW.minus(Duration.ofDays(1))).seated(5)).create();
        fixtures.event().title("Draft showtime show").showtime(s -> s.draft().seated(5)).create();
        fixtures.event().title("Cancelled showtime show").showtime(s -> s.cancelled().seated(5)).create();

        assertThat(ids(search())).containsExactly(visible);
        assertThat(ids(search("q", "show"))).containsExactly(visible);
    }

    // ------------------------------------------------------------------ Bộ lọc

    @Test
    void searchEvents_filtersByCityCategoryDatePrice() {
        UUID hnConcert = fixtures.event().title("HN concert").city("HN").category("CONCERT")
                .defaultPrice(300_000)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(5))).seated(5)).create().id();
        UUID hcmFootball = fixtures.event().title("HCM football").city("HCM").category("FOOTBALL")
                .defaultPrice(900_000)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(20))).seated(5)).create().id();
        UUID dnTheater = fixtures.event().title("DN theater").city("DN").category("THEATER")
                .defaultPrice(1_500_000)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(40))).seated(5)).create().id();

        assertThat(ids(search("city", "HN"))).containsExactly(hnConcert);
        assertThat(ids(search("category", "FOOTBALL"))).containsExactly(hcmFootball);
        assertThat(ids(search("from", at(10), "to", at(30)))).containsExactly(hcmFootball);
        assertThat(ids(search("from", at(10)))).containsExactly(hcmFootball, dnTheater);
        assertThat(ids(search("to", at(10)))).containsExactly(hnConcert);
        assertThat(ids(search("price_min", "500000", "price_max", "1000000"))).containsExactly(hcmFootball);
        assertThat(ids(search("price_min", "1000000"))).containsExactly(dnTheater);
        assertThat(ids(search("price_max", "300000"))).containsExactly(hnConcert);
        assertThat(ids(search("city", "HN", "category", "FOOTBALL"))).isEmpty();
    }

    @Test
    void searchEvents_showsVenueMatchingCityFilter_forTouringEvent() {
        Instant hnShow = NOW.plus(Duration.ofDays(3));
        Instant hcmShow = NOW.plus(Duration.ofDays(10));
        UUID tour = fixtures.event().title("Sơn Tùng M-TP Sky Tour")
                .showtime(s -> s.city("HN").venue("Sân Mỹ Đình").startsAt(hnShow).seated(5))
                .showtime(s -> s.city("HCM").venue("Sân Phú Thọ").startsAt(hcmShow).seated(5))
                .create().id();

        JsonNode all = search().path("data");
        assertThat(all).hasSize(1);
        assertThat(all.get(0).path("venue_name").asString()).isEqualTo("Sân Mỹ Đình");
        assertThat(all.get(0).path("city").asString()).isEqualTo("HN");
        assertThat(all.get(0).path("city_name").asString()).isEqualTo("Hà Nội");
        assertThat(Instant.parse(all.get(0).path("first_starts_at").asString())).isEqualTo(hnShow);

        JsonNode hcm = search("city", "HCM").path("data");
        assertThat(hcm).hasSize(1);
        assertThat(hcm.get(0).path("id").asString()).isEqualTo(tour.toString());
        assertThat(hcm.get(0).path("venue_name").asString()).isEqualTo("Sân Phú Thọ");
        assertThat(hcm.get(0).path("city").asString()).isEqualTo("HCM");
        assertThat(hcm.get(0).path("city_name").asString()).isEqualTo("TP. Hồ Chí Minh");
        assertThat(Instant.parse(hcm.get(0).path("first_starts_at").asString())).isEqualTo(hcmShow);

        // Lọc theo ngày cũng đổi suất đại diện: chỉ còn suất HCM.
        JsonNode later = search("from", at(5)).path("data");
        assertThat(later.get(0).path("venue_name").asString()).isEqualTo("Sân Phú Thọ");
    }

    @Test
    void searchEvents_returnsEventWithoutCity_whenVenueCityIsNull() {
        fixtures.event().title("Không có thành phố").create();
        jdbc.sql("UPDATE venue SET city = NULL").update();

        JsonNode data = search().path("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).has("city")).isFalse();
        assertThat(data.get(0).has("city_name")).isFalse();
    }

    @Test
    void searchEvents_priceFromIgnoresUnassignedTiers() {
        fixtures.event().title("Nhiều hạng giá")
                .tier("Rẻ chưa gán", 100_000)
                .tier("Thường", 800_000)
                .tier("VIP", 2_000_000)
                .showtime(s -> s.seated(5, "VIP").standing(50, "Thường"))
                .create();

        JsonNode multi = findByTitle(search(), "Nhiều hạng giá");
        assertThat(multi.path("price_from").asInt()).isEqualTo(800_000);
        // Hạng "Rẻ chưa gán" không tính vào bộ lọc giá.
        assertThat(search("price_max", "150000").path("data")).isEmpty();
        assertThat(search("price_min", "50000", "price_max", "850000").path("data")).hasSize(1);
    }

    @Test
    void searchEvents_marksOnSale_onlyWhenSaleWindowIsOpen() {
        fixtures.event().title("Đang bán").showtime(s -> s.onSale().seated(5)).create();
        fixtures.event().title("Chưa mở bán").showtime(s -> s.salesNotOpen().seated(5)).create();
        fixtures.event().title("Đã đóng bán").showtime(s -> s.salesClosed().seated(5)).create();

        JsonNode data = search().path("data");
        assertThat(findByTitle(data, "Đang bán").path("on_sale").asBoolean()).isTrue();
        assertThat(findByTitle(data, "Chưa mở bán").path("on_sale").asBoolean()).isFalse();
        assertThat(findByTitle(data, "Đã đóng bán").path("on_sale").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ Sắp xếp và phân trang

    @Test
    void searchEvents_sortsByRelevance_whenQueryGivenAndNoSort() {
        // Tạo bản gõ sai trước: nếu chỉ sắp theo id giảm dần thì nó sẽ đứng đầu.
        UUID fuzzy = fixtures.event().title("Son Tungg Live").create().id();
        UUID exact = fixtures.event().title("Son Tung").create().id();

        assertThat(ids(search("q", "son tung"))).containsExactly(exact, fuzzy);
        assertThat(ids(search("q", "son tung", "sort", "relevance"))).containsExactly(exact, fuzzy);
    }

    @Test
    void searchEvents_sortsByStartsAtWithoutQuery_andByPriceAndPopularity() {
        UUID late = fixtures.event().title("Muộn").defaultPrice(100_000)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(30))).seated(5).sold(1)).create().id();
        UUID soon = fixtures.event().title("Sớm").defaultPrice(900_000)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(2))).seated(5).standing(10).sold(7)).create().id();
        UUID noPrice = fixtures.event().title("Chưa có giá").tier("Chưa gán", 1)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(10))).seated(5, null)).create().id();
        jdbc.sql("DELETE FROM zone_price WHERE event_id = :e").param("e", noPrice).update();

        assertThat(ids(search())).containsExactly(soon, noPrice, late);
        assertThat(ids(search("sort", "price"))).containsExactly(late, soon, noPrice); // không giá xếp cuối
        assertThat(ids(search("sort", "popularity"))).containsExactly(soon, late, noPrice);
    }

    @Test
    void searchEvents_paginatesWithoutDuplicates_forEachSort() {
        Instant sameStart = NOW.plus(Duration.ofDays(5));
        for (int i = 0; i < 7; i++) {
            int n = i;
            fixtures.event().title("Show " + i)
                    // Cố ý trùng giờ, giá và số vé bán để bắt buộc phá hoà bằng id.
                    .defaultPrice(n < 4 ? 500_000 : 100_000L * (n + 1))
                    .showtime(s -> s.startsAt(n < 4 ? sameStart : sameStart.plus(Duration.ofHours(n)))
                            .seated(10).sold(n % 3))
                    .create();
        }
        String[][] cases = {{"relevance", "show"}, {"starts_at", null}, {"price", null}, {"popularity", null}};

        for (String[] c : cases) {
            Map<String, String> base = new LinkedHashMap<>();
            base.put("sort", c[0]);
            if (c[1] != null) {
                base.put("q", c[1]);
            }
            List<UUID> unpaged = ids(searchWith(base, Map.of("limit", "100")));

            List<UUID> paged = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("limit", "3");
                if (cursor != null) {
                    extra.put("cursor", cursor);
                }
                JsonNode page = searchWith(base, extra);
                paged.addAll(ids(page));
                cursor = nextCursor(page);
                pages++;
            } while (cursor != null && pages < 10);

            assertThat(unpaged).as("sort=%s", c[0]).hasSize(7).doesNotHaveDuplicates();
            assertThat(paged).as("sort=%s phân trang", c[0]).containsExactlyElementsOf(unpaged);
            assertThat(pages).as("sort=%s số trang", c[0]).isEqualTo(3);
        }
    }

    @Test
    void searchEvents_omitsNextCursor_onLastPage() {
        fixtures.event().title("Một").create();
        fixtures.event().title("Hai").create();

        assertThat(nextCursor(search("limit", "2"))).isNull();
        assertThat(nextCursor(search("limit", "1"))).isNotNull();
    }

    // ------------------------------------------------------------------ Lỗi 400

    @Test
    void searchEvents_returns400_whenRelevanceWithoutQuery() {
        fixtures.event().title("Show").create();

        JsonNode error = searchError("sort", "relevance");

        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("errors").get(0).path("field").asString()).isEqualTo("sort");
    }

    @Test
    void searchEvents_returns400_whenCursorDoesNotMatchFilters() {
        fixtures.event().title("Show A").city("HN").create();
        fixtures.event().title("Show B").city("HN").create();
        String cursor = nextCursor(search("city", "HN", "limit", "1"));
        assertThat(cursor).isNotNull();

        assertThat(searchError("city", "HCM", "limit", "1", "cursor", cursor).path("code").asString())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(searchError("city", "HN", "sort", "price", "limit", "1", "cursor", cursor)
                .path("errors").get(0).path("field").asString()).isEqualTo("cursor");
        // Cùng bộ lọc thì dùng được.
        assertThat(search("city", "HN", "limit", "1", "cursor", cursor).path("data")).hasSize(1);
    }

    @Test
    void searchEvents_returns400_whenCursorIsCorrupt() {
        assertThat(searchError("cursor", "khong-phai-cursor").path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void searchEvents_returns400_whenLimitOutOfRange() {
        JsonNode error = searchError("limit", "0");
        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("errors").get(0).path("field").asString()).isEqualTo("limit");
        assertThat(searchError("limit", "101").path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void searchEvents_returns400_whenRangesAreInvalid() {
        assertThat(searchError("from", at(10), "to", at(5)).path("errors").get(0).path("field").asString())
                .isEqualTo("from");
        assertThat(searchError("price_min", "900", "price_max", "100").path("code").asString())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(searchError("price_min", "-1").path("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(searchError("sort", "random").path("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(searchError("city", "SGN").path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ Helper

    private static String at(int daysFromNow) {
        return NOW.plus(Duration.ofDays(daysFromNow)).toString();
    }

    private JsonNode search(String... keyValues) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return searchWith(params, Map.of());
    }

    private JsonNode searchWith(Map<String, String> params, Map<String, String> extra) {
        String body = client.get().uri(uri(params, extra)).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return JSON.readTree(body);
    }

    private JsonNode searchError(String... keyValues) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        String body = client.get().uri(uri(params, Map.of())).exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();
        return JSON.readTree(body);
    }

    private static URI uri(Map<String, String> params, Map<String, String> extra) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/v1/events");
        params.forEach(builder::queryParam);
        extra.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("data").forEach(n -> ids.add(UUID.fromString(n.path("id").asString())));
        return ids;
    }

    private static String nextCursor(JsonNode page) {
        JsonNode node = page.path("next_cursor");
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static JsonNode findByTitle(JsonNode pageOrData, String title) {
        JsonNode data = pageOrData.has("data") ? pageOrData.path("data") : pageOrData;
        for (JsonNode n : data) {
            if (title.equals(n.path("title").asString())) {
                return n;
            }
        }
        throw new AssertionError("Không thấy sự kiện: " + title);
    }
}
