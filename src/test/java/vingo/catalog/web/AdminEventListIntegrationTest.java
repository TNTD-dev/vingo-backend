package vingo.catalog.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jayway.jsonpath.JsonPath;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class AdminEventListIntegrationTest extends IntegrationTest {
    private static final String PATH = "/api/v1/admin/events";
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-12T08:00:00Z");

    @Test
    void listAdminEvents_returnsAllStatusesAndSeparatePosters() {
        String prefix = uniquePrefix();
        var expected = new ArrayList<String>();
        for (String status : List.of("DRAFT", "PUBLISHED", "ENDED", "CANCELLED")) {
            expected.add(insertEvent(prefix + status, status, "FOOTBALL", CREATED).toString());
        }
        String response = getPage(Map.of("q", prefix, "limit", 100));
        assertThat(ids(response)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(JsonPath.<List<String>>read(response, "$.data[*].status"))
                .containsExactlyInAnyOrder("DRAFT", "PUBLISHED", "ENDED", "CANCELLED");
        assertThat(JsonPath.<List<String>>read(response, "$.data[*].poster_mobile_url"))
                .hasSize(4).allMatch("https://example.com/mobile.jpg"::equals);
        assertThat(JsonPath.<List<String>>read(response, "$.data[*].poster_desktop_url"))
                .hasSize(4).allMatch("https://example.com/desktop.jpg"::equals);
        assertThat(JsonPath.<List<String>>read(response, "$.data[*].created_at")).hasSize(4);
        assertThat(JsonPath.<List<String>>read(response, "$.data[*].updated_at")).hasSize(4);
        assertThat(JsonPath.<String>read(response, "$.next_cursor")).isNull();
    }

    @Test
    void listAdminEvents_combinesQueryStatusAndCategory() {
        String prefix = uniquePrefix();
        UUID expected = insertEvent(prefix + " Concert", "DRAFT", "CONCERT", CREATED);
        insertEvent(prefix + " Concert", "PUBLISHED", "CONCERT", CREATED);
        insertEvent(prefix + " Concert", "DRAFT", "FOOTBALL", CREATED);
        insertEvent(uniquePrefix(), "DRAFT", "CONCERT", CREATED);
        assertThat(ids(getPage(Map.of("q", "  " + prefix.toUpperCase(Locale.ROOT) + "  ",
                "status", "DRAFT", "category", "CONCERT")))).containsExactly(expected.toString());
        assertThat(ids(getPage(Map.of("q", prefix, "status", "PUBLISHED")))).hasSize(1);
        assertThat(ids(getPage(Map.of("q", prefix, "category", "CONCERT")))).hasSize(2);
    }

    @Test
    void listAdminEvents_treatsWildcardCharactersLiterally() {
        String prefix = uniquePrefix();
        UUID expected = insertEvent(prefix + "%_ticket", "DRAFT", "OTHER", CREATED);
        insertEvent(prefix + "ABticket", "DRAFT", "OTHER", CREATED);
        assertThat(ids(getPage(Map.of("q", prefix + "%_")))).containsExactly(expected.toString());
    }

    @Test
    void listAdminEvents_paginatesWithoutGapsWhenTimestampsMatch() {
        String prefix = uniquePrefix();
        var sameTimeIds = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            sameTimeIds.add(insertEvent(prefix + i, "DRAFT", "CONCERT", CREATED).toString());
        }
        var expected = new ArrayList<String>();
        expected.add(insertEvent(prefix + "newer", "DRAFT", "CONCERT", CREATED.plusHours(1)).toString());
        expected.addAll(sameTimeIds.stream().sorted(Comparator.reverseOrder()).toList());
        var actual = new ArrayList<String>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            Map<String, Object> params = new HashMap<>(Map.of("q", prefix, "status", "DRAFT",
                    "category", "CONCERT", "limit", 2));
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            String response = getPage(params);
            assertThat(ids(response)).hasSize(2);
            actual.addAll(ids(response));
            cursor = JsonPath.read(response, "$.next_cursor");
            if (cursor == null) {
                break;
            }
        }
        assertThat(cursor).isNull();
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void listAdminEvents_returnsPartialLastPageAndSurvivesNewerInsert() {
        String prefix = uniquePrefix();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            expected.add(insertEvent(prefix + i, "DRAFT", "OTHER", CREATED.plusHours(i)).toString());
        }
        String first = getPage(Map.of("q", prefix, "limit", 2));
        String cursor = JsonPath.read(first, "$.next_cursor");
        assertThat(cursor).isNotBlank();
        insertEvent(prefix + "inserted-between-pages", "DRAFT", "OTHER", CREATED.plusDays(1));
        String second = getPage(Map.of("q", prefix, "limit", 2, "cursor", cursor));
        assertThat(ids(second)).hasSize(1);
        var actual = new ArrayList<>(ids(first));
        actual.addAll(ids(second));
        assertThat(actual).containsExactly(expected.get(2), expected.get(1), expected.get(0));
        assertThat(JsonPath.<String>read(second, "$.next_cursor")).isNull();
    }

    @Test
    void listAdminEvents_defaultsToTwentyAndReturnsEmptyPageWithNullCursor() {
        String prefix = uniquePrefix();
        for (int i = 0; i < 21; i++) {
            insertEvent(prefix + i, "DRAFT", "OTHER", CREATED);
        }
        String response = getPage(Map.of("q", prefix));
        assertThat(ids(response)).hasSize(20);
        assertThat(JsonPath.<String>read(response, "$.next_cursor")).isNotBlank();
        String empty = getPage(Map.of("q", uniquePrefix()));
        assertThat(ids(empty)).isEmpty();
        assertThat(JsonPath.<String>read(empty, "$.next_cursor")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=abc", "status=UNKNOWN", "category=UNKNOWN",
            "cursor=***", "cursor=", "cursor=YWJj", "cursor=YWJjfGFiYw"})
    void listAdminEvents_returns400WhenParametersInvalid(String input) {
        String[] parts = input.split("=", 2);
        client.get().uri(builder -> builder.path(PATH).queryParam(parts[0], "{value}")
                        .build(Map.of("value", parts[1])))
                .headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listAdminEvents_returns401WithoutTokenAnd403ForBuyer() {
        client.get().uri(PATH).exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client.get().uri(PATH).headers(h -> h.setBearerAuth(tokenFor(Role.BUYER)))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    private String getPage(Map<String, ?> params) {
        return client.get().uri(builder -> {
            builder.path(PATH);
            params.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
            return builder.build(params);
        }).headers(h -> h.setBearerAuth(tokenFor(Role.ADMIN)))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private UUID insertEvent(String title, String status, String category, OffsetDateTime createdAt) {
        return jdbc.sql("""
                INSERT INTO event (title, status, category, poster_mobile_url, poster_desktop_url,
                                   created_at, updated_at, published_at)
                VALUES (:title, :status, :category, 'https://example.com/mobile.jpg',
                        'https://example.com/desktop.jpg', :createdAt, :createdAt,
                        CASE WHEN :status = 'PUBLISHED' THEN :createdAt ELSE NULL END)
                RETURNING id
                """).param("title", title).param("status", status).param("category", category)
                .param("createdAt", createdAt).query(UUID.class).single();
    }

    private static List<String> ids(String body) {
        return JsonPath.read(body, "$.data[*].id");
    }

    private static String uniquePrefix() {
        return "ev24-" + UUID.randomUUID();
    }
}
