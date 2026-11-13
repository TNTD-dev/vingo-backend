package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.support.CatalogFixture.Showtime;
import vingo.ticketing.TicketingService;

class AdminOrdersApiTest extends AdminOpsTestSupport {

    private final AdminOpsService adminOps;
    private Showtime otherShowtime;
    private String phone;
    private UUID paid;
    private UUID cancelled;
    private UUID review;
    private UUID expired;
    private UUID pending;
    private UUID otherPaid;

    @Autowired
    AdminOrdersApiTest(BookingService bookingService, TicketingService ticketingService,
                       PlatformTransactionManager transactionManager, AdminOpsService adminOps) {
        super(bookingService, ticketingService, transactionManager);
        this.adminOps = adminOps;
    }

    @BeforeEach
    void createOrdersAcrossBuyersEventsAndStates() {
        UUID otherBuyer = catalog.buyer();
        otherShowtime = catalog.onSaleShowtime();
        String otherEmail = otherBuyer + "@tickets.vingo.test";
        phone = "+84 9" + String.format(Locale.ROOT, "%07d", Math.floorMod(buyer.hashCode(), 10_000_000));
        String otherPhone = "+84 8" + String.format(Locale.ROOT, "%07d", Math.floorMod(otherBuyer.hashCode(), 10_000_000));
        var now = catalog.now();
        paid = readOrderFixture(buyer, showtime, "PAID", buyerEmail, phone, now.minusSeconds(3));
        cancelled = readOrderFixture(otherBuyer, otherShowtime, "CANCELLED", otherEmail, otherPhone, now.minusSeconds(4));
        review = readOrderFixture(buyer, otherShowtime, "NEEDS_REVIEW", buyerEmail, phone, now.minusSeconds(2));
        expired = readOrderFixture(otherBuyer, showtime, "EXPIRED", otherEmail, otherPhone, now.minusSeconds(1));
        pending = readOrderFixture(buyer, showtime, "PENDING", buyerEmail, phone, now);
        otherPaid = readOrderFixture(otherBuyer, otherShowtime, "PAID", otherEmail, otherPhone, now);
    }

    @Test
    void adminListOrders_filtersEventThroughShowtime() {
        String json = body(get(adminToken, "/api/v1/admin/orders?event_id={id}", otherShowtime.event().id()), 200);

        assertIds(json, otherPaid, review, cancelled);
        assertThat(strings(json, "$.data[*].event.id")).containsOnly(otherShowtime.event().id().toString());
    }

    @Test
    void adminListOrders_filtersShowtime() {
        String json = body(get(adminToken, "/api/v1/admin/orders?showtime_id={id}", showtime.id()), 200);

        assertIds(json, pending, expired, paid);
        assertThat(strings(json, "$.data[*].showtime.id")).containsOnly(showtime.id().toString());
    }

    @Test
    void adminListOrders_combinesFiltersWithAnd() {
        String uri = "/api/v1/admin/orders?status=PAID&event_id={event}&showtime_id={showtime}&q={q}";

        assertIds(body(get(adminToken, uri, showtime.event().id(), showtime.id(), buyerEmail), 200), paid);
        assertIds(body(get(adminToken, uri, otherShowtime.event().id(), showtime.id(), buyerEmail), 200));
        assertIds(body(get(adminToken, uri, showtime.event().id(), showtime.id(), "missing@vingo.test"), 200));
    }

    @Test
    void adminListOrders_matchesLowercaseOrderCodeExactlyAfterTrim() {
        String code = "Z" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        jdbc.sql("UPDATE orders SET code = :code WHERE id = :id").param("code", code).param("id", paid).update();

        assertIds(query("  " + code.toLowerCase(Locale.ROOT) + "  "), paid);
        assertIds(query(code.substring(0, 3)));
    }

    @Test
    void adminListOrders_matchesEmailIgnoringCaseAfterTrim() {
        assertIds(query("  " + buyerEmail.toUpperCase(Locale.ROOT) + "  "), pending, review, paid);
    }

    @Test
    void adminListOrders_matchesPhoneVerbatimAfterTrim() {
        assertIds(query("  " + phone + "  "), pending, review, paid);
        assertIds(query(phone.replace(" ", "")));
    }

    @Test
    void adminListOrders_doesNotMatchPartialEmailOrPhone() {
        assertIds(query(buyerEmail.substring(0, 12)));
        assertIds(query(phone.substring(0, phone.length() - 2)));
    }

    @Test
    void adminListOrders_doesNotInterpretWildcardsAroundOrInsideExistingEmail() {
        assertIds(query("%" + buyerEmail + "%"));
        assertIds(query(buyerEmail.replaceFirst("-", "_")));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 255})
    void adminListOrders_returns400ForQueryOutsideTrimmedLengthRange(int length) {
        problem(get(adminToken, "/api/v1/admin/orders?q={q}", "x".repeat(length)), 400, "VALIDATION_ERROR");
    }

    @Test
    void adminListOrders_validatesLengthAfterTrim() {
        problem(get(adminToken, "/api/v1/admin/orders?q={q}", "  ab  "), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " "})
    void adminListOrders_returns400ForBlankQuery(String q) {
        problem(get(adminToken, "/api/v1/admin/orders?q={q}", q), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 254})
    void adminListOrders_acceptsTrimmedLengthBoundaries(int length) {
        assertIds(query("  " + "x".repeat(length) + "  "));
    }

    @Test
    void adminListOrders_paginatesByTwoWithoutDuplicatesOrGapsAndEndsWithNullCursor() {
        String sharedEmail = UUID.randomUUID() + "@page.vingo.test";
        jdbc.sql("UPDATE orders SET buyer_email = :email, created_at = :at WHERE showtime_id IN (:first, :second)")
                .param("email", sharedEmail).param("at", catalog.now())
                .param("first", showtime.id()).param("second", otherShowtime.id()).update();
        var expected = jdbc.sql("SELECT id FROM orders WHERE buyer_email = :email ORDER BY created_at DESC, id DESC")
                .param("email", sharedEmail).query(String.class).list();

        readPages("/api/v1/admin/orders?q=" + sharedEmail, 2, expected);
    }

    @Test
    void adminListOrders_returns400ForMalformedCursor() {
        problem(get(adminToken, "/api/v1/admin/orders?cursor=not-a-cursor"), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void adminListOrders_returns400ForInvalidLimit(int limit) {
        problem(get(adminToken, "/api/v1/admin/orders?limit={limit}", limit), 400, "VALIDATION_ERROR");
    }

    @Test
    void adminListOrders_returns403ForBuyer() {
        problem(get(buyerToken, "/api/v1/admin/orders"), 403, "FORBIDDEN");
    }

    @Test
    void adminListOrders_returns401WithoutToken() {
        problem(get(null, "/api/v1/admin/orders"), 401, "UNAUTHORIZED");
    }

    @Test
    void adminListOrders_serviceRejectsBuyerWithoutHttpSecurity() {
        assertServiceRejectsBuyer(() -> adminOps.adminListOrders(null, null, null, null, null, 20));
    }

    private String query(String q) {
        return body(get(adminToken, "/api/v1/admin/orders?q={q}", q), 200);
    }

    private void assertIds(String json, UUID... ids) {
        assertThat(strings(json, "$.data[*].id")).containsExactlyElementsOf(List.of(ids).stream().map(UUID::toString).toList());
        assertThat(object(json)).containsEntry("next_cursor", null);
    }
}
