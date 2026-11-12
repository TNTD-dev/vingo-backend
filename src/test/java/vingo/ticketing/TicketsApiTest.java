package vingo.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Window;

class TicketsApiTest extends TicketingTestSupport {

    @Autowired
    TicketsApiTest(BookingService bookingService, TicketingService ticketingService,
                   PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void getTicket_returnsIssuedTicketAndCatalogDetailsForOwner() {
        UUID order = hold(token, showtime, seats(showtime.event().seat(1)));
        UUID id = payAndIssue(order).getFirst();
        var stored = ticket(id);

        String json = body(get(token, "/api/v1/tickets/{id}", id), 200);

        assertThat(text(json, "$.id")).isEqualTo(id.toString());
        assertThat(text(json, "$.code")).isEqualTo(stored.code()).isNotBlank();
        assertThat(text(json, "$.status")).isEqualTo("ISSUED");
        assertThat(text(json, "$.qr_token")).isEqualTo(stored.qrToken()).isNotBlank();
        assertThat(text(json, "$.order_code")).isEqualTo(jdbc.sql("SELECT code FROM orders WHERE id = :id")
                .param("id", order).query(String.class).single());
        assertThat(text(json, "$.zone")).isEqualTo("A");
        assertThat(text(json, "$.row")).isEqualTo("A");
        assertThat(((Number) JsonPath.read(json, "$.number")).intValue()).isEqualTo(1);
        assertThat(text(json, "$.tier")).isEqualTo("VIP");
        assertThat(text(json, "$.event.id")).isEqualTo(showtime.event().id().toString());
        assertThat(text(json, "$.showtime.id")).isEqualTo(showtime.id().toString());
    }

    @Test
    void getTicket_returns404ForAnotherBuyersTicket() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();

        problem(get(tokenFor(Role.BUYER), "/api/v1/tickets/{id}", id), 404, "NOT_FOUND");
    }

    @Test
    void getTicket_returns404ForUnknownId() {
        problem(get(token, "/api/v1/tickets/{id}", UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    void getTicket_returns401WithoutToken() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();

        problem(get(null, "/api/v1/tickets/{id}", id), 401, "UNAUTHORIZED");
    }

    @Test
    void getTicket_hidesQrTokenForVoidTicket() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();
        voidTicket(id);

        String json = body(get(token, "/api/v1/tickets/{id}", id), 200);

        assertThat(text(json, "$.id")).isEqualTo(id.toString());
        assertThat(text(json, "$.status")).isEqualTo("VOID");
        assertThat(object(json).get("qr_token")).isNull();
    }

    @Test
    void listMyTickets_returnsOnlyOwnersTickets() {
        var own = payAndIssue(hold(token, showtime, standing(showtime, 3)));
        var other = payAndIssue(hold(tokenFor(Role.BUYER), showtime, standing(showtime, 2)));

        String json = body(get(token, "/api/v1/tickets"), 200);

        assertThat(strings(json, "$.data[*].id"))
                .containsExactlyInAnyOrderElementsOf(own.stream().map(UUID::toString).toList())
                .doesNotContainAnyElementsOf(other.stream().map(UUID::toString).toList());
    }

    @Test
    void listMyTickets_upcomingAndDefaultExcludeFinishedShowtime() {
        var ids = issuePastAndUpcomingTickets();

        String explicit = body(get(token, "/api/v1/tickets?when=upcoming"), 200);
        String defaults = body(get(token, "/api/v1/tickets"), 200);

        assertThat(strings(explicit, "$.data[*].id")).containsExactly(ids.get(1).toString());
        assertThat(strings(defaults, "$.data[*].id")).containsExactly(ids.get(1).toString());
    }

    @Test
    void listMyTickets_pastIncludesOnlyFinishedShowtime() {
        var ids = issuePastAndUpcomingTickets();

        String json = body(get(token, "/api/v1/tickets?when=past"), 200);

        assertThat(strings(json, "$.data[*].id")).containsExactly(ids.getFirst().toString());
    }

    @Test
    void listMyTickets_filtersUsedStatus() {
        var ids = issueSeats(showtime.event().seat(1), showtime.event().seat(2));
        UUID used = ids.getFirst();
        jdbc.sql("UPDATE ticket SET status = 'USED', used_at = :now WHERE id = :id")
                .param("now", catalog.now()).param("id", used).update();

        String json = body(get(token, "/api/v1/tickets?status=USED"), 200);

        assertThat(strings(json, "$.data[*].id")).containsExactly(used.toString());
        assertThat(strings(json, "$.data[*].status")).containsExactly("USED");
    }

    @Test
    void listMyTickets_paginatesWithoutDuplicatesOrOmissionsAndEndsWithNullCursor() {
        var ids = payAndIssue(hold(token, showtime, standing(showtime, 5)));
        List<String> seen = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < 3; page++) {
            String json = body(cursor == null ? get(token, "/api/v1/tickets?limit=2")
                    : get(token, "/api/v1/tickets?limit=2&cursor={cursor}", cursor), 200);
            var pageIds = strings(json, "$.data[*].id");
            assertThat(pageIds).hasSize(page < 2 ? 2 : 1);
            seen.addAll(pageIds);
            cursor = text(json, "$.next_cursor");
            if (page < 2) {
                assertThat(cursor).isNotBlank();
                cursors.add(cursor);
            } else {
                assertThat(cursor).isNull();
            }
        }

        assertThat(cursors).doesNotHaveDuplicates();
        assertThat(seen).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
    }

    @Test
    void listMyTickets_rejectsLimitAbove100() {
        problem(get(token, "/api/v1/tickets?limit=101"), 400, "VALIDATION_ERROR");
    }

    @Test
    void listMyTickets_returns401WithoutToken() {
        problem(get(null, "/api/v1/tickets"), 401, "UNAUTHORIZED");
    }

    private List<UUID> issuePastAndUpcomingTickets() {
        UUID earlier = issueSeats(showtime.event().seat(1)).getFirst();
        var later = catalog.showtime(showtime.event(), new Window("SCHEDULED", Duration.ofDays(-1),
                Duration.ofDays(14), Duration.ofDays(14), Duration.ofDays(14).plusHours(2)));
        UUID upcoming = payAndIssue(hold(token, later, seats(later.event().seat(1)))).getFirst();
        OffsetDateTime end = jdbc.sql("SELECT ends_at FROM showtime WHERE id = :id")
                .param("id", showtime.id()).query(OffsetDateTime.class).single();
        clock.set(end.plusSeconds(1).toInstant());
        return List.of(earlier, upcoming);
    }
}
