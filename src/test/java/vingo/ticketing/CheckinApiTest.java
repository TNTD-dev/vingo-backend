package vingo.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.security.Role;

class CheckinApiTest extends TicketingTestSupport {

    private final JwtDecoder jwtDecoder;
    private String adminToken;
    private UUID admin;

    @Autowired
    CheckinApiTest(BookingService bookingService, TicketingService ticketingService,
                   PlatformTransactionManager transactionManager, JwtDecoder jwtDecoder) {
        super(bookingService, ticketingService, transactionManager);
        this.jwtDecoder = jwtDecoder;
    }

    @BeforeEach
    void createAdmin() {
        adminToken = tokenFor(Role.ADMIN);
        admin = UUID.fromString(jwtDecoder.decode(adminToken).getSubject());
    }

    @Test
    void checkIn_marksIssuedTicketUsedAndRecordsAdminGateAndScanTime() {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());

        String json = body(scan(adminToken, stored, showtime.id(), "Gate A"), 200);

        assertThat(text(json, "$.result")).isEqualTo("OK");
        assertThat(text(json, "$.ticket_code")).isEqualTo(stored.code());
        assertThat(text(json, "$.zone")).isEqualTo("A");
        assertThat(text(json, "$.row")).isEqualTo("A");
        assertThat(((Number) JsonPath.read(json, "$.number")).intValue()).isEqualTo(1);
        var scannedAt = OffsetDateTime.parse(text(json, "$.scanned_at"));
        assertThat(scannedAt.toInstant()).isEqualTo(clock.instant());
        var after = ticket(stored.id());
        assertThat(after.status()).isEqualTo("USED");
        assertThat(after.usedAt().toInstant()).isEqualTo(scannedAt.toInstant());
        assertThat(checkins(stored.id())).containsExactly(
                new CheckinRow(showtime.id(), admin, "Gate A", "OK", after.usedAt()));
    }

    @Test
    void checkIn_returnsAlreadyUsedAndRecordsAnotherScanWithoutChangingFirstUse() {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());
        body(scan(adminToken, stored, showtime.id(), "Gate A"), 200);
        var used = ticket(stored.id());
        clock.advance(Duration.ofSeconds(1));

        String json = body(scan(adminToken, stored, showtime.id(), "Gate B"), 409);

        assertThat(text(json, "$.result")).isEqualTo("ALREADY_USED");
        assertThat(OffsetDateTime.parse(text(json, "$.first_used_at")).toInstant())
                .isEqualTo(used.usedAt().toInstant());
        assertThat(ticket(stored.id())).isEqualTo(used);
        assertThat(checkins(stored.id())).containsExactly(
                new CheckinRow(showtime.id(), admin, "Gate A", "OK", used.usedAt()),
                new CheckinRow(showtime.id(), admin, "Gate B", "ALREADY_USED", catalog.now()));
    }

    @Test
    void checkIn_returnsTicketVoidAndStillRecordsScan() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();
        voidTicket(id);
        var stored = ticket(id);

        String json = body(scan(adminToken, stored, showtime.id(), "Gate A"), 409);

        assertThat(text(json, "$.result")).isEqualTo("TICKET_VOID");
        assertThat(ticket(id)).isEqualTo(stored);
        assertThat(checkins(id)).containsExactly(
                new CheckinRow(showtime.id(), admin, "Gate A", "TICKET_VOID", catalog.now()));
    }

    @Test
    void checkIn_returnsWrongShowtimeAndRecordsAttemptWithoutUsingTicket() {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());
        var wrong = catalog.onSaleShowtime();

        String json = body(scan(adminToken, stored, wrong.id(), "Gate C"), 409);

        assertThat(text(json, "$.result")).isEqualTo("WRONG_SHOWTIME");
        assertThat(ticket(stored.id())).isEqualTo(stored);
        assertThat(ticket(stored.id()).status()).isEqualTo("ISSUED");
        assertThat(checkins(stored.id())).containsExactly(
                new CheckinRow(wrong.id(), admin, "Gate C", "WRONG_SHOWTIME", catalog.now()));
    }

    @Test
    void checkIn_returns404AndDoesNotRecordScanForUnknownQrToken() {
        int before = showtimeCheckinCount();

        problem(checkIn(adminToken, "{\"qr_token\":\"%s\",\"showtime_id\":\"%s\"}"
                .formatted(UUID.randomUUID(), showtime.id())), 404, "NOT_FOUND");

        assertThat(showtimeCheckinCount()).isEqualTo(before);
    }

    @Test
    void checkIn_forbidsBuyerWithoutChangingTicketOrRecordingScan() {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());

        problem(scan(token, stored, showtime.id(), "Gate A"), 403, "FORBIDDEN");

        assertThat(ticket(stored.id())).isEqualTo(stored);
        assertThat(checkins(stored.id())).isEmpty();
    }

    @Test
    void checkIn_returns401WithoutToken() {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());

        problem(scan(null, stored, showtime.id(), "Gate A"), 401, "UNAUTHORIZED");

        assertThat(ticket(stored.id())).isEqualTo(stored);
        assertThat(checkins(stored.id())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"qr_token", "showtime_id"})
    void checkIn_rejectsMissingRequiredField(String missing) {
        String request = missing.equals("qr_token") ? "{\"showtime_id\":\"%s\"}".formatted(showtime.id())
                : "{\"qr_token\":\"unknown-token\"}";
        int before = showtimeCheckinCount();

        problem(checkIn(adminToken, request), 400, "VALIDATION_ERROR");

        assertThat(showtimeCheckinCount()).isEqualTo(before);
    }

    @Test
    @Timeout(60)
    void checkIn_allowsExactlyOneOfTwoConcurrentScanners() throws Exception {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<ScanResponse>> futures = new ArrayList<>();
        List<ScanResponse> responses = new ArrayList<>();
        try {
            for (String gate : List.of("Gate A", "Gate B")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start latch timed out");
                    var response = scan(adminToken, stored, showtime.id(), gate)
                            .expectBody(String.class).returnResult();
                    return new ScanResponse(response.getStatus().value(), response.getResponseBody());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("Both scanners ready").isTrue();
            start.countDown();
            for (var future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("Both scanners stopped").isTrue();
        }

        assertThat(responses).extracting(ScanResponse::status).containsExactlyInAnyOrder(200, 409);
        var used = ticket(stored.id());
        assertThat(used.status()).isEqualTo("USED");
        assertThat(used.usedAt()).isNotNull();
        for (var response : responses) {
            assertThat(text(response.json(), "$.result"))
                    .isEqualTo(response.status() == 200 ? "OK" : "ALREADY_USED");
            if (response.status() == 409) {
                assertThat(OffsetDateTime.parse(text(response.json(), "$.first_used_at")).toInstant())
                        .isEqualTo(used.usedAt().toInstant());
            }
        }
        var scans = checkins(stored.id());
        assertThat(scans).extracting(CheckinRow::result).containsExactlyInAnyOrder("OK", "ALREADY_USED");
        assertThat(scans).extracting(CheckinRow::gate).containsExactlyInAnyOrder("Gate A", "Gate B");
        assertThat(scans).extracting(CheckinRow::scannedBy).containsOnly(admin);
    }

    @Test
    void listShowtimeCheckins_returnsOnlyRequestedShowtimeNewestFirstWithCursorPagination() {
        var stored = ticket(issueSeats(showtime.event().seat(1)).getFirst());
        body(scan(adminToken, stored, showtime.id(), "Gate A"), 200);
        clock.advance(Duration.ofSeconds(1));
        body(scan(adminToken, stored, showtime.id(), "Gate A"), 409);
        clock.advance(Duration.ofSeconds(1));
        body(scan(adminToken, stored, showtime.id(), "Gate B"), 409);
        var other = catalog.onSaleShowtime();
        var otherTicket = ticket(payAndIssue(hold(token, other, seats(other.event().seat(1)))).getFirst());
        body(scan(adminToken, otherTicket, other.id(), "Gate D"), 200);
        var expected = checkins(stored.id()).reversed();

        String first = body(get(adminToken, "/api/v1/admin/showtimes/{id}/checkins?limit=2", showtime.id()), 200);
        String cursor = text(first, "$.next_cursor");
        assertThat(cursor).isNotBlank();
        String last = body(get(adminToken, "/api/v1/admin/showtimes/{id}/checkins?limit=2&cursor={cursor}",
                showtime.id(), cursor), 200);

        assertThat(strings(first, "$.data[*].ticket_code")).containsExactly(stored.code(), stored.code());
        assertThat(strings(last, "$.data[*].ticket_code")).containsExactly(stored.code());
        assertThat(strings(first, "$.data[*].result")).containsExactly("ALREADY_USED", "ALREADY_USED");
        assertThat(strings(last, "$.data[*].result")).containsExactly("OK");
        var times = new ArrayList<>(scannedTimes(first));
        times.addAll(scannedTimes(last));
        assertThat(times).doesNotHaveDuplicates()
                .containsExactlyElementsOf(expected.stream().map(row -> row.scannedAt().toInstant()).toList());
        assertThat(text(last, "$.next_cursor")).isNull();
    }

    @Test
    void listShowtimeCheckins_forbidsBuyer() {
        problem(get(token, "/api/v1/admin/showtimes/{id}/checkins", showtime.id()), 403, "FORBIDDEN");
    }

    @Test
    void listShowtimeCheckins_returns401WithoutToken() {
        problem(get(null, "/api/v1/admin/showtimes/{id}/checkins", showtime.id()), 401, "UNAUTHORIZED");
    }

    @Test
    void checkIn_completesHoldSaleIssueAndScanForStandingTicket() {
        UUID order = hold(token, showtime, standing(showtime, 1));
        UUID id = payAndIssue(order).getFirst();
        var stored = ticket(id);
        assertThat(stored.status()).isEqualTo("ISSUED");

        String json = body(scan(adminToken, stored, showtime.id(), "GA Gate"), 200);

        assertThat(text(json, "$.result")).isEqualTo("OK");
        assertThat(text(json, "$.ticket_code")).isEqualTo(stored.code());
        assertThat(text(json, "$.zone")).isEqualTo("GA");
        assertThat(object(json).get("row")).isNull();
        assertThat(object(json).get("number")).isNull();
        assertThat(ticket(id).status()).isEqualTo("USED");
        assertThat(checkins(id)).containsExactly(
                new CheckinRow(showtime.id(), admin, "GA Gate", "OK", catalog.now()));
    }

    private int showtimeCheckinCount() {
        return jdbc.sql("SELECT count(*) FROM checkin WHERE showtime_id = :id")
                .param("id", showtime.id()).query(Integer.class).single();
    }

    private List<Instant> scannedTimes(String json) {
        return strings(json, "$.data[*].scanned_at").stream()
                .map(OffsetDateTime::parse).map(OffsetDateTime::toInstant).toList();
    }

    private record ScanResponse(int status, String json) {}
}
