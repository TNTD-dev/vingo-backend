package vingo.catalog.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import vingo.catalog.EventService;
import vingo.search.SearchService;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;
import vingo.support.IntegrationTest;

class AdminEventUpdateIntegrationTest extends IntegrationTest {
    private static final String PATH = "/api/v1/admin/events/{event_id}";
    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private final EventService events;
    private final SearchService search;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private String token;

    @Autowired
    AdminEventUpdateIntegrationTest(EventService events, SearchService search,
            StringRedisTemplate redis, PlatformTransactionManager transactions) {
        this.events = events;
        this.search = search;
        this.redis = redis;
        this.tx = new TransactionTemplate(transactions);
    }

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        token = tokenFor(Role.ADMIN);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void updateEvent_changesOnlyTitleAndKeepsPostersAttributesAndTicketLimit() {
        UUID id = createDraft();
        var before = snapshot(id);
        clock.set(NOW.plusSeconds(60));
        patch(id, """
                {"title":"  Tên mới  "}
                """).expectStatus().isOk().expectBody()
                .jsonPath("$.title").isEqualTo("Tên mới")
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.max_tickets_per_order").isEqualTo(4)
                .jsonPath("$.poster_mobile_url").isEqualTo("https://example.com/mobile.jpg")
                .jsonPath("$.poster_desktop_url").isEqualTo("https://example.com/desktop.jpg")
                .jsonPath("$.attributes.home_team").isEqualTo("Đội A");
        var after = snapshot(id);
        assertThat(after.get("title")).isEqualTo("Tên mới");
        assertThat(jdbc.sql("SELECT updated_at FROM event WHERE id = :id")
                .param("id", id).query(OffsetDateTime.class).single())
                .isEqualTo(NOW.plusSeconds(60).atOffset(ZoneOffset.UTC));
        before.remove("title");
        before.remove("updated_at");
        after.remove("title");
        after.remove("updated_at");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void updateEvent_changesAllMetadataAndReplacesAttributesObject() {
        UUID id = createDraft();
        patch(id, """
                {"title":"Concert mới","description":"Giới thiệu mới","category":"CONCERT",
                 "attributes":{"artist":"Nghệ sĩ A"},
                 "poster_mobile_url":"https://example.com/new-mobile.jpg",
                 "poster_desktop_url":"https://example.com/new-desktop.jpg",
                 "age_limit":0,"max_tickets_per_order":20}
                """).expectStatus().isOk().expectBody()
                .jsonPath("$.category").isEqualTo("CONCERT")
                .jsonPath("$.attributes.artist").isEqualTo("Nghệ sĩ A")
                .jsonPath("$.attributes.home_team").doesNotExist()
                .jsonPath("$.age_limit").isEqualTo(0)
                .jsonPath("$.max_tickets_per_order").isEqualTo(20);
        var saved = snapshot(id);
        assertThat(saved.get("title")).isEqualTo("Concert mới");
        assertThat(saved.get("description")).isEqualTo("Giới thiệu mới");
        assertThat(saved.get("category")).isEqualTo("CONCERT");
        assertThat(saved.get("poster_mobile_url")).isEqualTo("https://example.com/new-mobile.jpg");
        assertThat(saved.get("poster_desktop_url")).isEqualTo("https://example.com/new-desktop.jpg");
        assertThat(saved.get("age_limit")).isEqualTo(0);
        assertThat(saved.get("max_tickets_per_order")).isEqualTo(20);
        assertThat(jdbc.sql("SELECT attributes ->> 'artist' FROM event WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("Nghệ sĩ A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", """
            {"title":null,"description":null,"category":null,"attributes":null,
             "poster_mobile_url":null,"poster_desktop_url":null,
             "age_limit":null,"max_tickets_per_order":null}
            """})
    void updateEvent_keepsAllDataAndDoesNotEvictCacheWhenNoValuesProvided(String body) {
        UUID id = createDraft();
        var before = snapshot(id);
        String generation = generation("event", id);
        clock.set(NOW.plusSeconds(60));
        patch(id, body).expectStatus().isOk();
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(generation("event", id)).isEqualTo(generation);
    }

    @Test
    void updateEvent_allowsEmptyDescriptionAndEmptyAttributes() {
        UUID id = createDraft();
        patch(id, "{\"description\":\"\",\"attributes\":{}}")
                .expectStatus().isOk().expectBody()
                .jsonPath("$.description").isEqualTo("")
                .jsonPath("$.attributes").isEmpty();
        assertThat(jdbc.sql("SELECT attributes = '{}'::jsonb FROM event WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\"\"}", "{\"title\":\"  \"}", "{\"category\":\"UNKNOWN\"}",
            "{\"age_limit\":-1}", "{\"max_tickets_per_order\":0}", "{\"max_tickets_per_order\":21}",
            "{\"attributes\":[]}", "{\"attributes\":\"text\"}",
            "{\"poster_mobile_url\":\"/relative.jpg\"}",
            "{\"poster_desktop_url\":\"ftp://example.com/a.jpg\"}",
            "{\"poster_mobile_url\":\"https:/missing-host.jpg\"}",
            "{\"poster_mobile_url\":\"https://bad url\"}",
            "{\"title\":\"Không được lưu\",\"age_limit\":-1}", "[]", "null"})
    void updateEvent_returns400AndPreservesDatabaseAndCacheWhenInvalid(String body) {
        UUID id = createDraft();
        var before = snapshot(id);
        String generation = generation("event", id);
        patch(id, body).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(generation("event", id)).isEqualTo(generation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDED", "CANCELLED"})
    void updateEvent_returns409ForClosedEvents(String status) {
        UUID id = catalog.event(EventSpec.defaults().withCity("HN").withStatus(status)).id();
        var before = snapshot(id);
        patch(id, "{\"title\":\"Tên mới\"}").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("EVENT_LOCKED");
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void updateEvent_returns404ForMissingEvent401WithoutTokenAnd403ForBuyer() {
        patch(UUID.randomUUID(), "{\"title\":\"Tên mới\"}").expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        UUID id = createDraft();
        var before = snapshot(id);
        client.patch().uri(PATH, id).contentType(MediaType.APPLICATION_JSON).body("{}")
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client.patch().uri(PATH, id).headers(h -> h.setBearerAuth(tokenFor(Role.BUYER)))
                .contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"Không được sửa\"}")
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void updateEvent_refreshesPublishedSearchAfterCommitAndKeepsPricesAndInventory() {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        var showtime = catalog.showtime(event, Window.onSale());
        String oldTitle = search.getEvent(event.id()).title();
        search.getSeatmap(showtime.id(), null);
        var inventory = jdbc.sql("SELECT * FROM showtime_seat WHERE showtime_id = :id ORDER BY seat_id")
                .param("id", showtime.id()).query().listOfRows();
        var stock = jdbc.sql("SELECT * FROM showtime_zone_stock WHERE showtime_id = :id ORDER BY zone_id")
                .param("id", showtime.id()).query().listOfRows();
        var prices = jdbc.sql("SELECT * FROM price_tier WHERE event_id = :id ORDER BY id")
                .param("id", event.id()).query().listOfRows();
        var zonePrices = jdbc.sql("SELECT * FROM zone_price WHERE event_id = :id ORDER BY zone_id")
                .param("id", event.id()).query().listOfRows();
        long eventGen = generationNumber("event", event.id());
        long showtimeGen = generationNumber("showtime", showtime.id());
        patch(event.id(), "{\"title\":\"Tên đã sửa công khai\",\"max_tickets_per_order\":4}")
                .expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("PUBLISHED")
                .jsonPath("$.showtimes[0].id").isEqualTo(showtime.id().toString())
                .jsonPath("$.price_tiers.length()").isEqualTo(2);
        assertThat(oldTitle).isNotEqualTo("Tên đã sửa công khai");
        assertThat(search.getEvent(event.id()).title()).isEqualTo("Tên đã sửa công khai");
        assertThat(search.getEvent(event.id()).maxTicketsPerOrder()).isEqualTo(4);
        assertThat(generationNumber("event", event.id())).isEqualTo(eventGen + 1);
        assertThat(generationNumber("showtime", showtime.id())).isEqualTo(showtimeGen + 1);
        assertThat(jdbc.sql("SELECT * FROM showtime_seat WHERE showtime_id = :id ORDER BY seat_id")
                .param("id", showtime.id()).query().listOfRows()).isEqualTo(inventory);
        assertThat(jdbc.sql("SELECT * FROM showtime_zone_stock WHERE showtime_id = :id ORDER BY zone_id")
                .param("id", showtime.id()).query().listOfRows()).isEqualTo(stock);
        assertThat(jdbc.sql("SELECT * FROM price_tier WHERE event_id = :id ORDER BY id")
                .param("id", event.id()).query().listOfRows()).isEqualTo(prices);
        assertThat(jdbc.sql("SELECT * FROM zone_price WHERE event_id = :id ORDER BY zone_id")
                .param("id", event.id()).query().listOfRows()).isEqualTo(zonePrices);
    }

    @Test
    void updateEvent_rollsBackWithoutInvalidatingSearch() {
        var event = catalog.event(EventSpec.defaults().withCity("HN"));
        var showtime = catalog.showtime(event, Window.notOpenYet());
        String title = search.getEvent(event.id()).title();
        String eventGen = generation("event", event.id());
        String showtimeGen = generation("showtime", showtime.id());
        var before = snapshot(event.id());
        tx.executeWithoutResult(status -> {
            events.update(event.id(), titlePatch("Tên sẽ rollback"));
            assertThat(generation("event", event.id())).isEqualTo(eventGen);
            status.setRollbackOnly();
        });
        assertThat(snapshot(event.id())).isEqualTo(before);
        assertThat(generation("event", event.id())).isEqualTo(eventGen);
        assertThat(generation("showtime", showtime.id())).isEqualTo(showtimeGen);
        assertThat(search.getEvent(event.id()).title()).isEqualTo(title);
    }

    @Test
    void updateEvent_serializesConcurrentPatchesWithoutLosingIndependentFields() throws Exception {
        UUID id = createDraft();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return events.update(id, titlePatch("Tên đồng thời"));
            });
            var second = executor.submit(() -> {
                start.await();
                return events.update(id, new EventService.UpdateEvent(
                        null, "Mô tả đồng thời", null, null, null, null, null, null));
            });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(snapshot(id)).containsEntry("title", "Tên đồng thời")
                .containsEntry("description", "Mô tả đồng thời")
                .containsEntry("max_tickets_per_order", 4);
    }

    @Test
    void updateEvent_doesNotOverwriteLegacyInvalidPosterWhenFieldIsAbsent() {
        UUID id = createDraft();
        jdbc.sql("UPDATE event SET poster_mobile_url = 'https://[bad' WHERE id = :id")
                .param("id", id).update();
        patch(id, "{\"title\":\"Tên mới\"}").expectStatus().isOk();
        assertThat(snapshot(id)).containsEntry("poster_mobile_url", "https://[bad")
                .containsEntry("poster_desktop_url", "https://example.com/desktop.jpg");
    }

    @Test
    void draftLifecycle_createReadListUpdateAndReadAgain() {
        UUID id = createDraft();
        client.get().uri(PATH, id).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.title").isEqualTo("Tên ban đầu");
        String title = "EV2-" + UUID.randomUUID();
        patch(id, "{\"title\":\"" + title + "\"}").expectStatus().isOk();
        client.get().uri(builder -> builder.path("/api/v1/admin/events")
                        .queryParam("q", title).queryParam("status", "DRAFT").build())
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(id.toString())
                .jsonPath("$.data[0].title").isEqualTo(title)
                .jsonPath("$.data[0].poster_mobile_url").isEqualTo("https://example.com/mobile.jpg");
        client.get().uri(PATH, id).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.title").isEqualTo(title)
                .jsonPath("$.max_tickets_per_order").isEqualTo(4)
                .jsonPath("$.attributes.home_team").isEqualTo("Đội A")
                .jsonPath("$.showtimes").isEmpty();
        client.get().uri("/api/v1/events/{id}", id).exchange().expectStatus().isNotFound();
    }

    private UUID createDraft() {
        String body = client.post().uri("/api/v1/admin/events").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body("""
                        {"title":"Tên ban đầu","category":"FOOTBALL","description":"Mô tả cũ",
                         "attributes":{"home_team":"Đội A"},
                         "poster_mobile_url":"https://example.com/mobile.jpg",
                         "poster_desktop_url":"https://example.com/desktop.jpg",
                         "age_limit":16,"max_tickets_per_order":4}
                        """).exchange().expectStatus().isCreated().expectBody(String.class)
                .returnResult().getResponseBody();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private RestTestClient.ResponseSpec patch(UUID id, String body) {
        return client.patch().uri(PATH, id).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }

    private Map<String, Object> snapshot(UUID id) {
        return new java.util.HashMap<>(jdbc.sql("SELECT * FROM event WHERE id = :id")
                .param("id", id).query().singleRow());
    }

    private String generation(String type, UUID id) {
        return redis.opsForValue().get("vingo:search:gen:" + type + ":" + id);
    }

    private long generationNumber(String type, UUID id) {
        String value = generation(type, id);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static EventService.UpdateEvent titlePatch(String title) {
        return new EventService.UpdateEvent(title, null, null, null, null, null, null, null);
    }
}
