package vingo.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void searchEvents_findsEvent_whenQueryHasExtraWords() {
        UUID target = fixtures.event().title("Sơn Tùng M-TP Sky Tour 2026").description("Đêm nhạc tại TP.HCM")
                .create().id();
        fixtures.event().title("Hòa nhạc Mùa Thu").create();

        assertThat(ids(search("q", "son tung concert"))).containsExactly(target);
    }

    @Test
    void searchEvents_doesNotLoosen_whenStrictMatchExists() {
        UUID target = fixtures.event().title("Sơn Tùng M-TP Sky Tour").create().id();
        fixtures.event().title("Lễ hội Sơn La").create();

        assertThat(ids(search("q", "son tung"))).containsExactly(target);
    }

    @Test
    void searchEvents_keepsLooseModeAcrossPages() {
        // "concert" không có trong sự kiện nào nên khớp chặt rỗng, phải nới. Mỗi sự kiện chỉ khớp một phần.
        Set<UUID> expected = new HashSet<>();
        expected.add(fixtures.event().title("Sơn Tùng Sky Tour").create().id());
        expected.add(fixtures.event().title("Sơn La Festival").create().id());
        expected.add(fixtures.event().title("Tùng Núi Live").create().id());
        expected.add(fixtures.event().title("Sơn Ca Đêm Nhạc").create().id());
        fixtures.event().title("Hòa nhạc Mùa Thu").create();

        List<UUID> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = cursor == null
                    ? search("q", "son tung concert", "limit", "1")
                    : search("q", "son tung concert", "limit", "1", "cursor", cursor);
            paged.addAll(ids(page));
            cursor = nextCursor(page);
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(paged).hasSize(4).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void searchEvents_doesNotLoosen_forSingleWordQuery() {
        fixtures.event().title("Sơn Tùng M-TP Sky Tour").create();

        assertThat(search("q", "concert").path("data")).isEmpty();
    }

    @Test
    void searchEvents_looseMatchRanksMoreMatchedWordsFirst() {
        UUID twoWords = fixtures.event().title("Sơn Tùng Sky Tour").create().id();
        UUID oneWord = fixtures.event().title("Sơn La Festival").create().id();

        // "concert" không có ở đâu nên chỉ khớp lỏng; sự kiện khớp 2 từ phải đứng trước sự kiện khớp 1 từ.
        assertThat(ids(search("q", "son tung concert"))).containsExactly(twoWords, oneWord);
    }

    @Test
    void searchEvents_returnsEmpty_whenQueryHasOnlySpecialCharacters() {
        fixtures.event().title("Sơn Tùng M-TP Sky Tour").create();

        assertThat(search("q", "!!! ...").path("data")).isEmpty();
    }

    // ------------------------------------------------------------------ Khớp theo sân và thành phố

    @Test
    void searchEvents_matchesVenueName_withAndWithoutDiacritics() {
        UUID a = fixtures.event().title("Đêm nhạc Thu").city("HN").venue("Sân vận động Mỹ Đình").create().id();
        UUID b = fixtures.event().title("Chung kết bóng đá").city("HN").venue("Sân vận động Mỹ Đình").create().id();
        fixtures.event().title("Lễ hội Phú Thọ").city("HCM").venue("Sân Phú Thọ").create();

        assertThat(ids(search("q", "Mỹ Đình"))).containsExactlyInAnyOrder(a, b);
        assertThat(ids(search("q", "my dinh"))).containsExactlyInAnyOrder(a, b);
        assertThat(ids(search("q", "MY DINH"))).containsExactlyInAnyOrder(a, b);
        assertThat(ids(search("q", "my din"))).containsExactlyInAnyOrder(a, b); // gõ dở
    }

    @Test
    void searchEvents_matchesCity_withAndWithoutDiacritics() {
        UUID hn = fixtures.event().title("Show Hà Nội A").city("HN").create().id();
        UUID hn2 = fixtures.event().title("Show B").city("HN").create().id();
        UUID dn = fixtures.event().title("Show C").city("DN").create().id();
        fixtures.event().title("Show D").city("HCM").create();

        assertThat(ids(search("q", "Hà Nội"))).containsExactlyInAnyOrder(hn, hn2);
        assertThat(ids(search("q", "ha noi"))).containsExactlyInAnyOrder(hn, hn2);
        assertThat(ids(search("q", "Đà Nẵng"))).containsExactly(dn);
        assertThat(ids(search("q", "da nang"))).containsExactly(dn);
    }

    @Test
    void searchEvents_ranksTitleMatchAboveVenueMatch() {
        // Tạo sự kiện khớp sân trước: nếu chỉ sắp theo id giảm dần thì sự kiện khớp tên sẽ đứng sau.
        UUID byVenue = fixtures.event().title("Đêm nhạc Thu").city("HN").venue("Sân Mỹ Đình").create().id();
        UUID byDescription = fixtures.event().title("Lễ hội mùa đông").description("Diễn ra gần Mỹ Đình")
                .city("HCM").venue("Sân Phú Thọ").create().id();
        UUID byTitle = fixtures.event().title("Mỹ Đình Live Concert").city("DN").venue("Sân Chi Lăng").create().id();

        assertThat(ids(search("q", "my dinh"))).containsExactly(byTitle, byVenue, byDescription);
    }

    @Test
    void searchEvents_shortQuery_doesNotReturnLooseTrigramMatches() {
        UUID sky = fixtures.event().title("Sơn Tùng M-TP Sky Tour 2026").create().id();

        assertThat(search("q", "x").path("data")).isEmpty();
        assertThat(search("q", "xy").path("data")).isEmpty();
        // Từ ngắn vẫn khớp tiền tố từ thật.
        assertThat(ids(search("q", "sk"))).containsExactly(sky);
    }

    @Test
    void searchEvents_typoInTitle_stillMatches() {
        UUID target = fixtures.event().title("Sơn Tùng M-TP Sky Tour 2026").city("HN").create().id();
        fixtures.event().title("Hòa nhạc Mùa Thu").city("HCM").create();

        assertThat(ids(search("q", "son tungg"))).containsExactly(target);
        assertThat(ids(search("q", "sky towr"))).containsExactly(target);
    }

    @Test
    void searchEvents_venueMatch_combinesWithCityAndDateFilters() {
        Instant hnShow = NOW.plus(Duration.ofDays(3));
        Instant hcmShow = NOW.plus(Duration.ofDays(20));
        UUID hnEarly = fixtures.event().title("Tour Miền Bắc").city("HN").venue("Sân Mỹ Đình")
                .showtime(s -> s.startsAt(hnShow).seated(5)).create().id();
        UUID hcmShowEvent = fixtures.event().title("Tour Miền Nam").city("HCM").venue("Sân Phú Thọ")
                .showtime(s -> s.startsAt(hcmShow).seated(5)).create().id();
        UUID hnOnly = fixtures.event().title("Đại nhạc hội").city("HN").venue("Sân Mỹ Đình")
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(40))).seated(5)).create().id();

        assertThat(ids(search("q", "my dinh"))).containsExactlyInAnyOrder(hnEarly, hnOnly);
        assertThat(search("q", "my dinh", "city", "HCM").path("data")).isEmpty();
        assertThat(ids(search("q", "phu tho", "city", "HCM"))).containsExactly(hcmShowEvent);
        assertThat(ids(search("q", "my dinh", "from", at(10), "to", at(30)))).isEmpty();
        assertThat(ids(search("q", "my dinh", "from", at(35)))).containsExactly(hnOnly);
        assertThat(ids(search("q", "ha noi", "to", at(10)))).containsExactly(hnEarly);
        JsonNode phuTho = search("q", "phu tho").path("data");
        assertThat(phuTho.get(0).path("venue_name").asString()).isEqualTo("Sân Phú Thọ");
        assertThat(phuTho.get(0).path("city").asString()).isEqualTo("HCM");
    }

    @Test
    void searchEvents_venueMatch_excludesDraftCancelledAndInactiveVenues() {
        UUID visible = fixtures.event().title("Show A").city("HN").venue("Sân Mỹ Đình").create().id();
        fixtures.event().title("Show B").city("HN").venue("Sân Mỹ Đình").draft().create();
        fixtures.event().title("Show C").city("HN").venue("Sân Mỹ Đình").status("CANCELLED").create();
        // Sự kiện chỉ có suất CANCELLED ở Mỹ Đình: không được hiện.
        fixtures.event().title("Show D").city("HN").venue("Sân Mỹ Đình")
                .showtime(s -> s.cancelled().seated(5)).create();
        // Sân đã ngừng dùng.
        fixtures.event().title("Show E").city("DN").venue("Sân Mỹ Đình Cũ").create();
        jdbc.sql("UPDATE venue SET deleted_at = now() WHERE name = 'Sân Mỹ Đình Cũ'").update();
        // Suất đã qua.
        fixtures.event().title("Show F").city("HN").venue("Sân Mỹ Đình")
                .showtime(s -> s.startsAt(NOW.minus(Duration.ofDays(1))).seated(5)).create();

        assertThat(ids(search("q", "my dinh"))).containsExactly(visible);
    }

    // ------------------------------------------------------------------ Phạm vi kết quả

    @Test
    void searchEvents_excludesDraftAndPastEvents() {
        UUID visible = fixtures.event().title("Visible show").create().id();
        fixtures.event().title("Draft show").draft().create();
        fixtures.event().title("Cancelled show").status("CANCELLED").create();
        fixtures.event().title("Past show")
                .showtime(s -> s.startsAt(NOW.minus(Duration.ofDays(1))).seated(5)).create();
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
        UUID dnConcert = fixtures.event().title("DN concert").city("DN").category("CONCERT")
                .defaultPrice(1_500_000)
                .showtime(s -> s.startsAt(NOW.plus(Duration.ofDays(40))).seated(5)).create().id();

        assertThat(ids(search("city", "HN"))).containsExactly(hnConcert);
        assertThat(ids(search("category", "FOOTBALL"))).containsExactly(hcmFootball);
        assertThat(ids(search("from", at(10), "to", at(30)))).containsExactly(hcmFootball);
        assertThat(ids(search("from", at(10)))).containsExactly(hcmFootball, dnConcert);
        assertThat(ids(search("to", at(10)))).containsExactly(hnConcert);
        assertThat(ids(search("price_min", "500000", "price_max", "1000000"))).containsExactly(hcmFootball);
        assertThat(ids(search("price_min", "1000000"))).containsExactly(dnConcert);
        assertThat(ids(search("price_max", "300000"))).containsExactly(hnConcert);
        assertThat(ids(search("city", "HN", "category", "FOOTBALL"))).isEmpty();
    }

    @Test
    void searchEvents_filtersEventsByCity() {
        Instant hnShow = NOW.plus(Duration.ofDays(3));
        Instant hcmShow = NOW.plus(Duration.ofDays(20));
        fixtures.event().title("Show HN").city("HN").venue("Sân Mỹ Đình")
                .showtime(s -> s.startsAt(hnShow).seated(5)).create();
        UUID hcmId = fixtures.event().title("Show HCM").city("HCM").venue("Sân Phú Thọ")
                .showtime(s -> s.startsAt(hcmShow).seated(5)).create().id();

        JsonNode hcm = search("city", "HCM").path("data");
        assertThat(hcm).hasSize(1);
        assertThat(hcm.get(0).path("id").asString()).isEqualTo(hcmId.toString());
        assertThat(hcm.get(0).path("venue_name").asString()).isEqualTo("Sân Phú Thọ");
        assertThat(hcm.get(0).path("city").asString()).isEqualTo("HCM");
        assertThat(hcm.get(0).path("city_name").asString()).isEqualTo("TP. Hồ Chí Minh");
        assertThat(Instant.parse(hcm.get(0).path("first_starts_at").asString())).isEqualTo(hcmShow);
    }

    @Test
    void searchEvents_matchesCityNamesAndLegacyCodesInSameFilter() {
        UUID legacy = fixtures.event().title("Sân cũ").city("HN").create().id();
        UUID mapped = fixtures.event().title("Sân mới").city("Thành phố Hà Nội").create().id();
        fixtures.event().title("Sân khác").city("Hải Phòng").create();

        assertThat(ids(search("city", "HN"))).containsExactlyInAnyOrder(legacy, mapped);
        assertThat(ids(search("city", "Hà Nội"))).containsExactlyInAnyOrder(legacy, mapped);
    }

    @Test
    void searchEvents_returnsEventWithoutCity_whenVenueCityIsNull() {
        fixtures.event().title("Không có thành phố").create();
        jdbc.sql("UPDATE venue SET city=NULL").update();

        JsonNode data = search().path("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).has("city")).isFalse();
        assertThat(data.get(0).has("city_name")).isFalse();
    }

    @Test
    void searchEvents_returnsPriceFromAboveIntRange() {
        fixtures.event().title("Vé siêu đắt").defaultPrice(3_000_000_000L).create();

        JsonNode data = search().path("data");

        assertThat(data.get(0).path("price_from").asLong()).isEqualTo(3_000_000_000L);
        assertThat(search("price_min", "2500000000").path("data")).hasSize(1);
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
        jdbc.sql("DELETE FROM event_zone WHERE event_id = :e").param("e", noPrice).update();

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
        assertThat(searchError("city", "X".repeat(101)).path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void searchEvents_acceptsOpenCity_fromFlatVenueFields() {
        String city = "PROVINCE-" + UUID.randomUUID();
        UUID target = fixtures.event().title("Sự kiện ở địa phương mới").city(city).create().id();

        JsonNode result = search("city", city);
        assertThat(ids(result)).containsExactly(target);
        assertThat(result.path("data").get(0).path("city").asString()).isEqualTo(city);
        assertThat(ids(search("city", "UNKNOWN-" + UUID.randomUUID()))).isEmpty();
    }

    @Test
    void searchEvents_omitsPoster_whenPosterUrlInvalid() {
        UUID id = fixtures.event().title("Poster hỏng").create().id();
        jdbc.sql("UPDATE event SET poster_desktop_url = 'http://bad url', poster_mobile_url = NULL WHERE id = :id").param("id", id).update();

        JsonNode data = search("q", "poster hong").path("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("id").asString()).isEqualTo(id.toString());
        assertThat(data.get(0).has("poster_url")).isFalse();
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
