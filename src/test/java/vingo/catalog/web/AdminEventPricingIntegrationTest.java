package vingo.catalog.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import vingo.shared.security.Role;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;
import vingo.support.IntegrationTest;

class AdminEventPricingIntegrationTest extends IntegrationTest {

    private static final String PATH
            = "/api/v1/admin/events/{id}/{operation}";

    private String token;

    @BeforeEach
    void prepare() {
        clock.set(Instant.parse("2026-11-12T09:00:00Z"));
        token = tokenFor(Role.ADMIN);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void putPriceTiers_createsTiersAndKeepsIdsOnRepeatedRequest() {
        UUID eventId = newDraft();

        var body = List.of(
                Map.of(
                        "name", "VIP",
                        "price", 500000,
                        "color", "#C8A2C8"
                ),
                Map.of(
                        "name", "Phổ thông",
                        "price", 200000
                )
        );

        put(eventId, "price-tiers", body)
                .expectStatus().isOk();

        List<UUID> before = tierIds(eventId);

        put(eventId, "price-tiers", body)
                .expectStatus().isOk();

        assertThat(tierIds(eventId))
                .containsExactlyElementsOf(before);

        assertThat(before).hasSize(2);
    }

    @Test
    void putPriceTiers_returns400WithoutPartialChanges_whenNamesDuplicate() {
        UUID eventId = newDraft();

        put(eventId, "price-tiers", List.of(
                Map.of("name", "VIP", "price", 500000)
        )).expectStatus().isOk();

        var before = tierRows(eventId);

        put(eventId, "price-tiers", List.of(
                Map.of("name", "VIP", "price", 600000),
                Map.of("name", " VIP ", "price", 300000)
        ))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_ERROR");

        assertThat(tierRows(eventId)).isEqualTo(before);
    }

    @Test
    void putPriceTiers_rejectsTierIdFromAnotherEvent() {
        UUID first = newDraft();
        UUID second = newDraft();

        put(first, "price-tiers", List.of(
                Map.of("name", "VIP", "price", 500000)
        )).expectStatus().isOk();

        UUID foreignTier = tierIds(first).getFirst();

        put(second, "price-tiers", List.of(
                Map.of(
                        "id", foreignTier,
                        "name", "VIP",
                        "price", 100000
                )
        ))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_ERROR");

        assertThat(tierIds(second)).isEmpty();
    }

    @Test
    void pricingEndpoints_requireAdmin() {
        UUID eventId = newDraft();

        for (String operation : List.of(
                "price-tiers", "zone-prices"
        )) {
            client.put().uri(PATH, eventId, operation)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of())
                    .exchange()
                    .expectStatus().isUnauthorized();

            client.put().uri(PATH, eventId, operation)
                    .headers(h -> h.setBearerAuth(
                            tokenFor(Role.BUYER)
                    ))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of())
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    private UUID newDraft() {
        return jdbc.sql("""
                INSERT INTO event (title, category, status)
                VALUES (:title, 'FOOTBALL', 'DRAFT')
                RETURNING id
                """)
                .param("title", "EV4-" + UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    private RestTestClient.ResponseSpec put(
            UUID eventId,
            String operation,
            Object body
    ) {
        return client.put()
                .uri(PATH, eventId, operation)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private List<UUID> tierIds(UUID eventId) {
        return jdbc.sql("""
                SELECT id FROM price_tier
                WHERE event_id = :id
                ORDER BY id
                """)
                .param("id", eventId)
                .query(UUID.class)
                .list();
    }

    private List<Map<String, Object>> tierRows(UUID eventId) {
        return jdbc.sql("""
                SELECT * FROM price_tier
                WHERE event_id = :id
                ORDER BY id
                """)
                .param("id", eventId)
                .query()
                .listOfRows();
    }

    @Test
    void putZonePrices_replacesMappingsAndRejectsForeignZoneAtomically() {
        var event = catalog.event(
                EventSpec.defaults()
                        .withCity("HN")
                        .withStatus("DRAFT")
        );

        catalog.showtime(event, Window.notOpenYet());

        var other = catalog.event(
                EventSpec.defaults()
                        .withCity("DN")
                        .withStatus("DRAFT")
        );

        // Chuyển cả hai khu sang hạng VIP.
        put(event.id(), "zone-prices", List.of(
                Map.of(
                        "zone_id", event.seatedZoneId(),
                        "price_tier_id", event.vipPriceTierId()
                ),
                Map.of(
                        "zone_id", event.standingZoneId(),
                        "price_tier_id", event.vipPriceTierId()
                )
        )).expectStatus().isOk();

        var before = zoneRows(event.id());

        assertThat(before).hasSize(2);
        assertThat(before).allSatisfy(row
                -> assertThat(row.get("price_tier_id"))
                        .isEqualTo(event.vipPriceTierId())
        );

        // Một dòng đúng và một khu của sự kiện khác.
        put(event.id(), "zone-prices", List.of(
                Map.of(
                        "zone_id", event.seatedZoneId(),
                        "price_tier_id", event.gaPriceTierId()
                ),
                Map.of(
                        "zone_id", other.seatedZoneId(),
                        "price_tier_id", event.vipPriceTierId()
                )
        ))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_ERROR");

        assertThat(zoneRows(event.id())).isEqualTo(before);
    }

    @Test
    void putPriceTiers_rejectsRemovingAssignedTier() {
        var event = catalog.event(
                EventSpec.defaults()
                        .withCity("HN")
                        .withStatus("DRAFT")
        );

        var before = tierRows(event.id());

        // Bỏ hạng GA, nhưng khu đứng vẫn đang dùng nó.
        put(event.id(), "price-tiers", List.of(
                Map.of(
                        "id", event.vipPriceTierId(),
                        "name", "VIP",
                        "price", 500000
                )
        ))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_ERROR");

        assertThat(tierRows(event.id())).isEqualTo(before);
    }

    @Test
    void pricingEndpoints_lockAtOpeningAndRemainLockedAfterSalesClose() {
        Instant start = Instant.parse("2026-11-12T09:00:00Z");
        clock.set(start);

        var event = catalog.event(
                EventSpec.defaults().withCity("HN")
        );

        catalog.showtime(event, Window.notOpenYet());

        // Window.notOpenYet mở bán sau 1 ngày.
        clock.set(start.plusSeconds(86400));

        for (String operation : List.of(
                "price-tiers", "zone-prices"
        )) {
            put(event.id(), operation, List.of())
                    .expectStatus().isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("EVENT_LOCKED");
        }

        // Đóng bán/kết thúc rồi vẫn không được mở khóa giá.
        clock.set(start.plusSeconds(10 * 86400));

        for (String operation : List.of(
                "price-tiers", "zone-prices"
        )) {
            put(event.id(), operation, List.of())
                    .expectStatus().isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("EVENT_LOCKED");
        }
    }

    @Test
    void pricingEndpoints_rejectChangesWhenExpiredOrderExists() {
        var event = catalog.event(
                EventSpec.defaults()
                        .withCity("HN")
                        .withStatus("DRAFT")
        );

        var showtime = catalog.showtime(
                event, Window.notOpenYet()
        );

        catalog.order(
                catalog.buyer(),
                showtime.id(),
                "EXPIRED",
                clock.instant()
                        .atOffset(java.time.ZoneOffset.UTC)
                        .plusMinutes(10)
        );

        for (String operation : List.of(
                "price-tiers", "zone-prices"
        )) {
            put(event.id(), operation, List.of())
                    .expectStatus().isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("EVENT_LOCKED");
        }
    }

    private List<Map<String, Object>> zoneRows(UUID eventId) {
        return jdbc.sql("""
            SELECT * FROM zone_price
            WHERE event_id = :id
            ORDER BY zone_id
            """)
                .param("id", eventId)
                .query()
                .listOfRows();
    }
}
