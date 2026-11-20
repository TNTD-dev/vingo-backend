package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;

class OrderBuyerSnapshotTest extends BookingTestSupport {

    private static final String BUYER_NAME = "Nguyễn Văn An";
    private static final String BUYER_PHONE = "+84901234567";
    private final BookingService booking;
    private final TransactionTemplate transactions;
    private Showtime showtime;
    private UUID buyer;
    private String token;
    private String email;

    @Autowired
    OrderBuyerSnapshotTest(BookingService booking, PlatformTransactionManager transactionManager) {
        this.booking = booking;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void createBuyerAndShowtime() {
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        token = buyerToken(buyer);
        email = buyer + "@profile.vingo.test";
    }

    @Test
    void markSold_preservesBuyerSnapshotStoredOnOrder() {
        UUID order = holdOrderWithBuyer();

        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);

        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertSnapshot(order);
    }

    @Test
    void markSold_snapshotsStayWhenMissingInventoryMovesOrderToReview() {
        UUID order = holdOrderWithBuyer();
        makeOverdue(order);
        holdOk(buyerToken(catalog.buyer()), showtime.id(), seats(showtime.event().seat(1)));

        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.NEEDS_REVIEW);

        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertSnapshot(order);
    }

    @Test
    void markNeedsReview_keepsSnapshotForOverduePendingOrder() {
        UUID order = holdOrderWithBuyer();
        makeOverdue(order);

        transactions.executeWithoutResult(status -> booking.markNeedsReview(order, ReviewReason.PAID_AFTER_EXPIRY));

        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertSnapshot(order);
    }

    @Test
    void adminOrders_findByUppercaseBuyerEmailAndExposeSnapshotInDetail() {
        UUID order = holdOrderWithBuyer();
        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);
        String adminToken = tokenFor(Role.ADMIN);

        String list = body(get(adminToken, "/api/v1/admin/orders?q={email}", email.toUpperCase(Locale.ROOT)), 200);
        String detail = body(get(adminToken, "/api/v1/admin/orders/{id}", order), 200);

        assertThat(strings(list, "$.data[*].id")).containsExactly(order.toString());
        Map<String, Object> actualBuyer = JsonPath.read(detail, "$.buyer");
        assertThat(actualBuyer).containsEntry("full_name", BUYER_NAME)
                .containsEntry("email", email).containsEntry("phone", BUYER_PHONE);
    }

    /** v2: người mua nằm trên chính đơn (không còn buyer_profile), do payment ghi lúc tạo thanh toán. */
    private UUID holdOrderWithBuyer() {
        UUID order = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1))));
        jdbc.sql("""
                        UPDATE ticket_order SET buyer_name = :name, buyer_email = :email, buyer_phone = :phone
                        WHERE id = :id
                        """)
                .param("name", BUYER_NAME).param("email", email).param("phone", BUYER_PHONE)
                .param("id", order).update();
        return order;
    }

    private Map<String, Object> snapshot(UUID order) {
        return jdbc.sql("SELECT buyer_name, buyer_email, buyer_phone FROM ticket_order WHERE id = :id")
                .param("id", order).query().singleRow();
    }

    private void assertSnapshot(UUID order) {
        assertThat(snapshot(order)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "buyer_name", BUYER_NAME, "buyer_email", email, "buyer_phone", BUYER_PHONE));
    }
}
