package vingo.shared;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import vingo.support.IntegrationTest;

/** Chốt chặn cuối ở DB chống bán trùng (lớp 2 và 4 trong tài liệu kiến trúc). */
class SchemaConstraintsTest extends IntegrationTest {

    private UUID showtimeId;
    private UUID seatMapId;
    private UUID zoneId;
    private UUID standingZoneId;
    private UUID seatId;

    @BeforeEach
    void seedCatalog() {
        var showtime = catalog.onSaleShowtime();
        showtimeId = showtime.id();
        seatMapId = showtime.event().layoutId();
        zoneId = showtime.event().seatedZoneId();
        standingZoneId = showtime.event().standingZoneId();
        seatId = showtime.event().seat(1);
    }

    @Test
    void zoneStock_rejectsOversell_whenSoldPlusHeldExceedsCapacity() {
        jdbc.sql("UPDATE standing_inventory SET capacity = 5, sold = 3, held = 2 WHERE showtime_id = :s AND zone_id = :z")
                .param("s", showtimeId).param("z", standingZoneId).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE standing_inventory SET held = held + 1 WHERE showtime_id = :s AND zone_id = :z")
                .param("s", showtimeId).param("z", standingZoneId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ticket_rejectsSecondLiveTicket_forSameSeatAndShowtime() {
        UUID order = insertPendingOrder(catalog.buyer());
        UUID skuPriceId = jdbc.sql("""
                        SELECT sp.id FROM sku_price sp
                        JOIN sku s ON s.id = sp.sku_id
                        JOIN showtime sh ON sh.event_id = s.event_id
                        WHERE sh.id = :s
                        ORDER BY sp.effective_from DESC LIMIT 1
                        """)
                .param("s", showtimeId).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO order_seat (order_id, showtime_id, seat_id, sku_price_id, unit_price)
                        VALUES (:o, :s, :seat, :sp, 500000)
                        """)
                .param("o", order).param("s", showtimeId).param("seat", seatId).param("sp", skuPriceId).update();
        insertTicket(order);

        assertThatThrownBy(() -> insertTicket(order)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orders_rejectsSecondPendingOrder_forSameUserAndShowtime() {
        UUID user = catalog.buyer();
        insertPendingOrder(user);

        assertThatThrownBy(() -> insertPendingOrder(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertPendingOrder(UUID user) {
        return jdbc.sql("""
                        INSERT INTO ticket_order (code, user_id, showtime_id, total_amount, expires_at)
                        VALUES (upper(substr(md5(random()::text), 1, 6)), :u, :s, 500000, now() + interval '10 minutes')
                        RETURNING id
                        """)
                .param("u", user).param("s", showtimeId).query(UUID.class).single();
    }

    private void insertTicket(UUID orderId) {
        jdbc.sql("""
                        INSERT INTO ticket (order_id, showtime_id, seat_id, code)
                        VALUES (:o, :s, :seat, gen_random_uuid()::text)
                        """)
                .param("o", orderId).param("s", showtimeId).param("seat", seatId)
                .update();
    }

    @Test
    void users_allowDuplicateUnverifiedPhone_evenWhenAnotherUserVerifiedIt() {
        UUID owner = catalog.buyer();
        String phone = jdbc.sql("SELECT phone FROM user_account WHERE id = :id")
                .param("id", owner).query(String.class).single();
        for (int i = 0; i < 2; i++) {
            jdbc.sql("UPDATE user_account SET phone = :phone, phone_verified_at = NULL WHERE id = :id")
                    .param("phone", phone).param("id", catalog.buyer()).update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM user_account WHERE phone = :phone")
                .param("phone", phone).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void users_rejectTwoVerifiedOwnersOfOnePhone() {
        UUID first = catalog.buyer();
        UUID second = catalog.buyer();
        assertThatThrownBy(() -> jdbc.sql("UPDATE user_account SET phone = (SELECT phone FROM user_account WHERE id = :first) WHERE id = :second")
                .param("first", first).param("second", second).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void users_rejectVerifiedTimestampWithoutPhone() {
        UUID user = catalog.buyer();
        assertThatThrownBy(() -> jdbc.sql("UPDATE user_account SET phone = NULL WHERE id = :id")
                .param("id", user).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void otpSession_requiresPhoneForPhonePurpose() {
        UUID user = catalog.buyer();
        assertThatThrownBy(() -> insertOtp(user, "PHONE", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERIFY", "RESET"})
    void otpSession_rejectsPhoneForOtherPurposes(String purpose) {
        UUID user = catalog.buyer();
        assertThatThrownBy(() -> insertOtp(user, purpose, "+84912345678"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertOtp(UUID user, String purpose, String phone) {
        var now = OffsetDateTime.now(clock);
        jdbc.sql("""
                INSERT INTO otp_verification (user_id, purpose, phone, client_key, code_hash, sent_at, expires_at)
                VALUES (:user, :purpose::otp_purpose, :phone, :clientKey, 'hash', :now, :expires)
                """).param("user", user).param("purpose", purpose)
                .param("phone", phone).param("clientKey", UUID.randomUUID())
                .param("now", now).param("expires", now.plusMinutes(5)).update();
    }
}
