package vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        jdbc.sql("""
                        INSERT INTO order_seat (order_id, showtime_id, seat_id, ticket_type_price_id, unit_price)
                        SELECT :o, si.showtime_id, si.seat_id, sp.id, sp.price
                        FROM seat_inventory si
                        JOIN showtime sh ON sh.id = si.showtime_id
                        JOIN event_zone ez ON ez.event_id = sh.event_id AND ez.zone_id = si.zone_id
                        JOIN ticket_type_price sp ON sp.ticket_type_id = ez.ticket_type_id
                        WHERE si.showtime_id = :s AND si.seat_id = :seat AND sp.effective_from <= :now
                        ORDER BY sp.effective_from DESC LIMIT 1
                        """)
                .param("o", order).param("s", showtimeId).param("seat", seatId).param("now", OffsetDateTime.now(clock)).update();
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

    @ParameterizedTest
    @ValueSource(strings = {"VERIFY", "RESET"})
    void emailOtp_requiresTargetEmail(String purpose) {
        UUID user = catalog.buyer();
        assertThatThrownBy(() -> insertOtp(user, purpose, null, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertOtp(user, purpose, "target@example.invalid", 0);
    }

    @Test
    void emailOtp_allowsConfigurableAttemptLimit_butRejectsNegativeAttempts() {
        UUID user = catalog.buyer();
        insertOtp(user, "VERIFY", "target@example.invalid", 6);
        assertThatThrownBy(() -> insertOtp(user, "VERIFY", "target@example.invalid", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void googleAccount_requiresVerifiedEmail() {
        UUID user = catalog.buyer();
        String subject = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.sql("UPDATE user_account SET google_sub = :sub, email_verified_at = NULL WHERE id = :id")
                .param("sub", subject).param("id", user).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_google_email_verified_check");
        jdbc.sql("UPDATE user_account SET google_sub = :sub, password_hash = NULL WHERE id = :id")
                .param("sub", subject).param("id", user).update();
    }

    @Test
    void adminAccount_requiresPassword_unlessDeleted() {
        UUID user = catalog.buyer();
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE user_account SET role = 'ADMIN', password_hash = NULL, google_sub = :sub WHERE id = :id
                """).param("sub", UUID.randomUUID().toString()).param("id", user).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_admin_password_check");
        jdbc.sql("UPDATE user_account SET role = 'ADMIN' WHERE id = :id").param("id", user).update();
        jdbc.sql("""
                UPDATE user_account SET deleted_at = :now, password_hash = NULL, phone = NULL WHERE id = :id
                """).param("now", OffsetDateTime.now(clock)).param("id", user).update();
    }

    @ParameterizedTest
    @CsvSource({"password_hash, hash", "google_sub, subject", "phone, +84912345678", "avatar_url, https://example.invalid/avatar"})
    void deletedAccount_rejectsRemainingPersonalData(String column, String value) {
        UUID user = catalog.buyer();
        jdbc.sql("""
                UPDATE user_account SET deleted_at = :now, password_hash = NULL, phone = NULL WHERE id = :id
                """).param("now", OffsetDateTime.now(clock)).param("id", user).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE user_account SET " + column + " = :value WHERE id = :id")
                .param("value", value).param("id", user).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_deleted_personal_data_check");
    }

    @Test
    void userAccounts_allowSharedContactPhone() {
        UUID first = catalog.buyer();
        UUID second = catalog.buyer();
        jdbc.sql("UPDATE user_account SET phone = (SELECT phone FROM user_account WHERE id = :first) WHERE id = :second")
                .param("first", first).param("second", second).update();
        assertThat(jdbc.sql("SELECT count(DISTINCT phone) FROM user_account WHERE id IN (:first, :second)")
                .param("first", first).param("second", second).query(Integer.class).single()).isOne();
    }

    private void insertOtp(UUID user, String purpose, String targetEmail, int attempts) {
        var now = OffsetDateTime.now(clock);
        jdbc.sql("""
                INSERT INTO otp_verification (user_id, purpose, target_email, client_key, code_hash, attempts, sent_at, expires_at)
                VALUES (:user, :purpose::otp_purpose, :email, :clientKey, 'hash', :attempts, :now, :expires)
                """).param("user", user).param("purpose", purpose)
                .param("email", targetEmail).param("attempts", attempts).param("clientKey", UUID.randomUUID())
                .param("now", now).param("expires", now.plusMinutes(5)).update();
    }
}
