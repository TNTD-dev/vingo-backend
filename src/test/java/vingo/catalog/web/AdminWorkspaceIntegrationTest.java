package vingo.catalog.web;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vingo.api.model.Venue;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.VenueSchemaFixture;

/** Các ca hợp đồng mới; chưa chạy trong lần triển khai theo yêu cầu người dùng. */
class AdminWorkspaceIntegrationTest extends IntegrationTest {
    private UUID admin;
    private String token;

    @BeforeEach
    void admin() {
        admin=catalog.buyer();
        jdbc.sql("UPDATE users SET role='ADMIN' WHERE id=:id").param("id",admin).update();
        token=tokenFor(admin,Role.ADMIN);
    }

    @Test
    void venueDetails_returnsCanonicalLocationAndUpdaterOutsideLegacyCities() {
        var body=Map.of("name","Sân kiểm tra","type","STADIUM","location",Map.of(
                "display_address","Hải Phòng","latitude",20.86,"longitude",106.68,
                "city_code","HP","source","MANUAL_MAP_PIN"));
        Venue venue=client.post().uri("/api/v1/admin/venues").headers(h->h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isCreated()
                .expectBody(Venue.class).returnResult().getResponseBody();
        client.get().uri("/api/v1/admin/venues/{id}",venue.getId()).headers(h->h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.location.city_code").isEqualTo("HP")
                .jsonPath("$.address").isEqualTo("Hải Phòng").jsonPath("$.updated_by").isEqualTo(admin.toString())
                .jsonPath("$.layout_count").isEqualTo(0);
    }

    @Test
    void layoutTree_onlyReturnsSelectedTypeWithOwnVersions() {
        UUID venue=jdbc.sql("INSERT INTO venue(name,type) VALUES ('Sân cây','STADIUM') RETURNING id")
                .query(UUID.class).single();
        UUID football=VenueSchemaFixture.draft(jdbc,venue);
        jdbc.sql("UPDATE seat_layout SET layout_type='FOOTBALL' WHERE id=:id").param("id",football).update();
        VenueSchemaFixture.draft(jdbc,venue);
        client.get().uri("/api/v1/admin/venues/{id}/layout-tree?layout_type=FOOTBALL",venue)
                .headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1).jsonPath("$.data[0].id").isEqualTo(football.toString())
                .jsonPath("$.layout_type").isEqualTo("FOOTBALL");
    }

    @Test
    void workspaceEndpoints_rejectBuyerAndMissingLayoutType() {
        String buyer=tokenFor(Role.BUYER);
        UUID venue=catalog.event().venueId();
        client.get().uri("/api/v1/admin/venues/{id}/layout-tree?layout_type=CONCERT",venue)
                .headers(h->h.setBearerAuth(buyer)).exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/admin/venues/{id}/layout-tree",venue)
                .headers(h->h.setBearerAuth(token)).exchange().expectStatus().isBadRequest();
    }
}
