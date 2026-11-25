package vingo.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import vingo.support.CatalogFixture;
import vingo.support.CatalogFixture.Window;
import vingo.support.IntegrationTest;
import vingo.support.VenueSchemaFixture;

/**
 * Kiểm tra 4 checklist đầu tiên trong docs/ticket-sku-redesign.md:
 * 1. Bảng ticket_type, ticket_type_price, mở rộng sku, khóa ngoại ghép, CHECK ghế/khu đứng, index tra giá.
 * 2. Xác định hạng từ dữ liệu cũ và gộp/đồng bộ biểu giá.
 * 3. Sinh 1 SKU cho mỗi cặp (ghế, hạng) ở khu ngồi và (khu, hạng) ở khu đứng; đối chiếu số SKU, tồn kho và giá.
 * 4. Chuyển sang giá theo ticket_type_price, giữ đường đọc đơn cũ (sku_price_id) và giữ nguyên unit_price đã chốt.
 */
class TicketSkuRedesignMigrationTest extends IntegrationTest {

    @Test
    void sample25SeatsAnd2Tiers_generates25SkusAnd50InventoryRowsAcross2Showtimes() {
        OffsetDateTime now = catalog.now();
        UUID buyer = catalog.buyer();
        UUID venueId = jdbc.sql("INSERT INTO venue (name, city) VALUES ('Sân 25 ghế', 'HN') RETURNING id")
                .query(UUID.class).single();
        UUID layoutId = VenueSchemaFixture.draft(jdbc, venueId);

        UUID zoneA = jdbc.sql("""
                        INSERT INTO zone (seat_map_id, code, name, kind, capacity)
                        VALUES (:layout, 'ZA', 'Khu A', 'SEATED', NULL) RETURNING id
                        """)
                .param("layout", layoutId).query(UUID.class).single();
        UUID zoneB = jdbc.sql("""
                        INSERT INTO zone (seat_map_id, code, name, kind, capacity)
                        VALUES (:layout, 'ZB', 'Khu B', 'SEATED', NULL) RETURNING id
                        """)
                .param("layout", layoutId).query(UUID.class).single();

        List<UUID> seatsA = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            seatsA.add(jdbc.sql("""
                            INSERT INTO seat (seat_map_id, zone_id, zone_kind, row_label, number)
                            VALUES (:layout, :zone, 'SEATED', 'A', :num) RETURNING id
                            """)
                    .param("layout", layoutId).param("zone", zoneA).param("num", i)
                    .query(UUID.class).single());
        }
        List<UUID> seatsB = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            seatsB.add(jdbc.sql("""
                            INSERT INTO seat (seat_map_id, zone_id, zone_kind, row_label, number)
                            VALUES (:layout, :zone, 'SEATED', 'B', :num) RETURNING id
                            """)
                    .param("layout", layoutId).param("zone", zoneB).param("num", i)
                    .query(UUID.class).single());
        }

        UUID eventId = jdbc.sql("""
                        INSERT INTO event (seat_map_id, title, category, status, max_tickets_per_order)
                        VALUES (:layout, 'Concert 25 ghế', 'CONCERT', 'PUBLISHED', 10) RETURNING id
                        """)
                .param("layout", layoutId).query(UUID.class).single();

        UUID vipTypeId = jdbc.sql("""
                        INSERT INTO ticket_type (event_id, name, color)
                        VALUES (:event, 'VIP', '#FF0000') RETURNING id
                        """)
                .param("event", eventId).query(UUID.class).single();
        UUID standardTypeId = jdbc.sql("""
                        INSERT INTO ticket_type (event_id, name, color)
                        VALUES (:event, 'Standard', '#00AAFF') RETURNING id
                        """)
                .param("event", eventId).query(UUID.class).single();

        jdbc.sql("""
                        INSERT INTO ticket_type_price (ticket_type_id, price, effective_from, created_by)
                        VALUES (:vip, 800000, :eff, :user), (:std, 400000, :eff, :user)
                        """)
                .param("vip", vipTypeId).param("std", standardTypeId)
                .param("eff", now.minusDays(5)).param("user", buyer).update();

        jdbc.sql("""
                        INSERT INTO event_zone (event_id, seat_map_id, zone_id, ticket_type_id)
                        VALUES (:event, :layout, :za, :vip), (:event, :layout, :zb, :std)
                        """)
                .param("event", eventId).param("layout", layoutId)
                .param("za", zoneA).param("vip", vipTypeId)
                .param("zb", zoneB).param("std", standardTypeId).update();

        for (int s = 0; s < 2; s++) {
            UUID showtimeId = jdbc.sql("""
                            INSERT INTO showtime (event_id, seat_map_id, starts_at, ends_at, sale_opens_at, sale_closes_at)
                            VALUES (:event, :layout, :starts, :ends, :opens, :closes) RETURNING id
                            """)
                    .param("event", eventId).param("layout", layoutId)
                    .param("starts", now.plusDays(10 + s)).param("ends", now.plusDays(10 + s).plusHours(2))
                    .param("opens", now.minusDays(1)).param("closes", now.plusDays(9 + s))
                    .query(UUID.class).single();

            jdbc.sql("""
                            INSERT INTO seat_inventory (showtime_id, seat_map_id, seat_id, zone_id, status)
                            SELECT :showtime, :layout, id, zone_id, 'AVAILABLE'::seat_status
                            FROM seat WHERE seat_map_id = :layout
                            """)
                    .param("showtime", showtimeId).param("layout", layoutId).update();
        }

        // 2 hạng vé, đúng 25 SKU ghế ngồi (10 VIP + 15 Standard), 50 dòng tồn ghế cho 2 suất
        int tierCount = jdbc.sql("SELECT count(*) FROM ticket_type WHERE event_id = :e")
                .param("e", eventId).query(Integer.class).single();
        int seatSkuCount = jdbc.sql("SELECT count(*) FROM sku WHERE event_id = :e AND seat_id IS NOT NULL")
                .param("e", eventId).query(Integer.class).single();
        int vipSkuCount = jdbc.sql("SELECT count(*) FROM sku WHERE event_id = :e AND ticket_type_id = :t AND seat_id IS NOT NULL")
                .param("e", eventId).param("t", vipTypeId).query(Integer.class).single();
        int stdSkuCount = jdbc.sql("SELECT count(*) FROM sku WHERE event_id = :e AND ticket_type_id = :t AND seat_id IS NOT NULL")
                .param("e", eventId).param("t", standardTypeId).query(Integer.class).single();
        int inventoryCount = jdbc.sql("""
                        SELECT count(*) FROM seat_inventory si
                        JOIN showtime st ON st.id = si.showtime_id
                        WHERE st.event_id = :e
                        """)
                .param("e", eventId).query(Integer.class).single();

        assertThat(tierCount).isEqualTo(2);
        assertThat(seatSkuCount).isEqualTo(25);
        assertThat(vipSkuCount).isEqualTo(10);
        assertThat(stdSkuCount).isEqualTo(15);
        assertThat(inventoryCount).isEqualTo(50);
    }

    @Test
    void skuConstraints_enforceCompositeForeignKeysAndSeatOrStandingCheck() {
        var showtime = catalog.onSaleShowtime();
        var event = showtime.event();
        var otherEvent = catalog.event();

        // 1. Không cho phép SKU vừa có seat_id vừa có zone_id
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_id, zone_kind)
                        VALUES (:e, :tt, :layout, :seat, :zone, 'SEATED')
                        """)
                .param("e", event.id()).param("tt", event.vipPriceTierId())
                .param("layout", event.layoutId()).param("seat", event.seat(1))
                .param("zone", event.standingZoneId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // 2. Không cho phép SKU trỏ tới ticket_type của sự kiện khác
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_kind)
                        VALUES (:e, :otherTt, :layout, :seat, 'SEATED')
                        """)
                .param("e", event.id()).param("otherTt", otherEvent.vipPriceTierId())
                .param("layout", event.layoutId()).param("seat", event.seat(1)).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // 3. Không cho phép SKU vé đứng trỏ tới zone SEATED
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO sku (event_id, ticket_type_id, seat_map_id, zone_id, zone_kind)
                        VALUES (:e, :tt, :layout, :seatedZone, 'STANDING')
                        """)
                .param("e", event.id()).param("tt", event.vipPriceTierId())
                .param("layout", event.layoutId()).param("seatedZone", event.seatedZoneId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // 4. Không cho phép trùng (event_id, seat_id, ticket_type_id)
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO sku (event_id, ticket_type_id, seat_map_id, seat_id, zone_kind)
                        VALUES (:e, :tt, :layout, :seat, 'SEATED')
                        """)
                .param("e", event.id()).param("tt", event.vipPriceTierId())
                .param("layout", event.layoutId()).param("seat", event.seat(1)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderAndTicketReaders_preserveLockedUnitPriceAndVerifySkuPriceAndEventZoneSkuIdCleanedUp() {
        var event = catalog.event();
        var showtime = catalog.showtime(event, Window.onSale());
        UUID buyer = catalog.buyer();

        // Kiểm tra bảng sku_price đã được dọn và cột sku_id trên event_zone đã được xóa
        Boolean skuPriceTableExists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name = 'sku_price'
                        )
                        """).query(Boolean.class).single();
        Boolean eventZoneSkuIdColumnExists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'event_zone' AND column_name = 'sku_id'
                        )
                        """).query(Boolean.class).single();
        assertThat(skuPriceTableExists).isFalse();
        assertThat(eventZoneSkuIdColumnExists).isFalse();

        // Đơn giữ giá theo ticket_type_price_id + sku_id, sau đó hạng vé đổi giá mới
        UUID orderId = catalog.order(buyer, showtime.id(), "PAID", catalog.now().plusMinutes(10));
        UUID seat1 = event.seat(1);
        UUID seat1SkuId = jdbc.sql("""
                        SELECT id FROM sku WHERE event_id = :e AND seat_id = :seat AND ticket_type_id = :tt
                        """)
                .param("e", event.id()).param("seat", seat1).param("tt", event.vipPriceTierId())
                .query(UUID.class).single();
        UUID vipTypePriceId = jdbc.sql("""
                        SELECT id FROM ticket_type_price WHERE ticket_type_id = :tt ORDER BY effective_from DESC LIMIT 1
                        """)
                .param("tt", event.vipPriceTierId()).query(UUID.class).single();

        jdbc.sql("""
                        INSERT INTO order_seat (
                            order_id, showtime_id, seat_id, sku_id, ticket_type_id, ticket_type_price_id, unit_price
                        )
                        VALUES (:o, :s, :seat, :sku, :tt, :ttp, :price)
                        """)
                .param("o", orderId).param("s", showtime.id()).param("seat", seat1)
                .param("sku", seat1SkuId).param("tt", event.vipPriceTierId())
                .param("ttp", vipTypePriceId).param("price", CatalogFixture.VIP_PRICE)
                .update();

        // Đổi giá mới cho hạng VIP
        jdbc.sql("""
                        INSERT INTO ticket_type_price (ticket_type_id, price, effective_from, created_by)
                        VALUES (:tt, 999000, :now, :user)
                        """)
                .param("tt", event.vipPriceTierId())
                .param("now", catalog.now())
                .param("user", buyer)
                .update();

        UUID ticket1Id = jdbc.sql("""
                        INSERT INTO ticket (order_id, showtime_id, seat_id, code)
                        VALUES (:o, :s, :seat, :code) RETURNING id
                        """)
                .param("o", orderId).param("s", showtime.id()).param("seat", seat1)
                .param("code", "NEW-" + UUID.randomUUID().toString().substring(0, 8))
                .query(UUID.class).single();

        // Kiểm tra API /orders/{id} và /tickets/{id} đọc đúng hạng và giữ nguyên unit_price đã chốt
        String token = tokenFor(buyer, vingo.shared.security.Role.BUYER);
        client.get().uri("/api/v1/orders/{id}", orderId)
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].seat_id").isEqualTo(seat1.toString())
                .jsonPath("$.items[0].tier").isEqualTo("VIP")
                .jsonPath("$.items[0].unit_price").isEqualTo((int) CatalogFixture.VIP_PRICE);

        client.get().uri("/api/v1/tickets/{id}", ticket1Id)
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tier").isEqualTo("VIP");
    }
}
