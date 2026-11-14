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
    private static final String BUYER_PHONE = "0901234567";
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
    void markSold_snapshotsBuyerProfileOnPendingOrder() {
        createBuyerProfile();
        UUID order = holdOrder();
        assertThat(orderStatus(order)).isEqualTo("PENDING");
        assertThat(snapshot(order)).containsEntry("buyer_email", null);

        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);

        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertProfileSnapshot(order);
    }

    @Test
    void markSold_preservesFirstSnapshotWhenProfileChanges() {
        createBuyerProfile();
        UUID order = holdOrder();
        jdbc.sql("""
                        UPDATE orders SET buyer_name = :name, buyer_email = :email, buyer_phone = :phone
                        WHERE id = :id
                        """)
                .param("name", BUYER_NAME).param("email", email).param("phone", BUYER_PHONE)
                .param("id", order).update();
        jdbc.sql("""
                        UPDATE buyer_profile SET full_name = 'Tên mới', email = :email,
                                                 phone = '0987654321', updated_at = :now
                        WHERE user_id = :user
                        """)
                .param("email", buyer + "@changed.vingo.test").param("now", catalog.now())
                .param("user", buyer).update();

        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);

        assertProfileSnapshot(order);
    }

    @Test
    void markSold_succeedsWithoutBuyerProfileAndLeavesSnapshotNull() {
        UUID order = holdOrder();

        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);

        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(snapshot(order)).containsEntry("buyer_name", null)
                .containsEntry("buyer_email", null).containsEntry("buyer_phone", null);
    }

    @Test
    void markSold_snapshotsProfileWhenMissingInventoryMovesOrderToReview() {
        createBuyerProfile();
        UUID order = holdOrder();
        makeOverdue(order);
        holdOk(buyerToken(catalog.buyer()), showtime.id(), seats(showtime.event().seat(1)));

        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.NEEDS_REVIEW);

        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertProfileSnapshot(order);
    }

    @Test
    void markNeedsReview_snapshotsProfileForOverduePendingOrder() {
        createBuyerProfile();
        UUID order = holdOrder();
        makeOverdue(order);

        transactions.executeWithoutResult(status -> booking.markNeedsReview(order, ReviewReason.PAID_AFTER_EXPIRY));

        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertProfileSnapshot(order);
    }

    @Test
    void markDuplicatePaymentForReview_snapshotsProfileForPaidOrder() {
        UUID order = paidOrderWithoutSnapshot();
        createBuyerProfile();

        transactions.executeWithoutResult(status -> booking.markDuplicatePaymentForReview(order));

        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertProfileSnapshot(order);
    }

    @Test
    void markSuspectedPaymentForReview_snapshotsProfileForPaidOrder() {
        UUID order = paidOrderWithoutSnapshot();
        createBuyerProfile();

        transactions.executeWithoutResult(status -> booking.markSuspectedPaymentForReview(order));

        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertProfileSnapshot(order);
    }

    @Test
    void adminOrders_findByUppercaseProfileEmailAndExposeSnapshotInDetail() {
        createBuyerProfile();
        UUID order = holdOrder();
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

    private void createBuyerProfile() {
        jdbc.sql("""
                        INSERT INTO buyer_profile (user_id, full_name, email, phone, updated_at)
                        VALUES (:user, :name, :email, :phone, :now)
                        """)
                .param("user", buyer).param("name", BUYER_NAME).param("email", email)
                .param("phone", BUYER_PHONE).param("now", catalog.now()).update();
    }

    private UUID holdOrder() {
        return orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1))));
    }

    private UUID paidOrderWithoutSnapshot() {
        UUID order = holdOrder();
        var outcome = transactions.execute(status -> booking.markSold(order));
        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);
        // Tạo profile sau khi bán để test không vô tình dựa vào snapshot của markSold.
        assertThat(snapshot(order)).containsEntry("buyer_email", null);
        return order;
    }

    private Map<String, Object> snapshot(UUID order) {
        return jdbc.sql("SELECT buyer_name, buyer_email, buyer_phone FROM orders WHERE id = :id")
                .param("id", order).query().singleRow();
    }

    private void assertProfileSnapshot(UUID order) {
        assertThat(snapshot(order)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "buyer_name", BUYER_NAME, "buyer_email", email, "buyer_phone", BUYER_PHONE));
    }
}
