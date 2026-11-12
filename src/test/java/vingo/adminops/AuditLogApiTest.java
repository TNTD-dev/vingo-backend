package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.ticketing.TicketingService;

class AuditLogApiTest extends AdminOpsTestSupport {

    @Autowired
    AuditLogApiTest(BookingService bookingService, TicketingService ticketingService,
                    PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void listAuditLogs_filtersEntityAndIdAndReturnsNewestFirstWithSnapshots() {
        UUID order = reviewSeats(showtime.event().seat(1), showtime.event().seat(2));
        body(resolve(order, "ISSUE_TICKETS"), 200);
        var tickets = ticketIds(order);
        UUID target = tickets.getFirst();
        body(ticketAction(adminToken, target, "reissue-qr", REASON_BODY), 200);
        clock.advance(Duration.ofSeconds(1));
        ticketAction(adminToken, target, "resend", null).expectStatus().isEqualTo(202);
        body(ticketAction(adminToken, tickets.getLast(), "void", REASON_BODY), 200);

        String json = body(get(adminToken, "/api/v1/admin/audit-logs?entity=ticket&entity_id={id}", target), 200);

        assertThat(strings(json, "$.data[*].id"))
                .containsExactlyElementsOf(audits(target).stream().map(row -> row.id().toString()).toList());
        var data = rows(json);
        assertThat(data).hasSize(2);
        data.forEach(row -> {
            assertThat(row).containsEntry("entity", "ticket").containsEntry("entity_id", target.toString())
                    .containsEntry("actor_id", admin.toString()).containsEntry("via_agent", false)
                    .containsKeys("after", "at");
        });
        assertThat(data.getFirst()).containsEntry("action", "RESEND_TICKET");
        // NON_NULL có thể lược bỏ before null; snapshot có dữ liệu vẫn phải được trả đầy đủ.
        assertThat(data.getFirst().get("before")).isNull();
        assertThat(data.getFirst().get("after"))
                .isEqualTo(object(audits(target).getFirst().afterJson()));
        assertThat(data.getLast()).containsEntry("action", "REISSUE_QR").containsEntry("reason", REASON)
                .containsEntry("before", Map.of("status", "ISSUED"))
                .containsEntry("after", Map.of("status", "ISSUED"));
        assertLastPage(json);

        String wrongEntity = body(get(adminToken,
                "/api/v1/admin/audit-logs?entity=order&entity_id={id}", target), 200);
        assertThat(rows(wrongEntity)).isEmpty();
        String orderPage = body(get(adminToken,
                "/api/v1/admin/audit-logs?entity=order&entity_id={id}", order), 200);
        assertThat(rows(orderPage)).hasSize(1);
        assertThat(rows(orderPage).getFirst()).containsEntry("action", "RESOLVE_REVIEW")
                .containsEntry("before", Map.of("status", "NEEDS_REVIEW"))
                .containsEntry("after", Map.of("status", "PAID"));
    }

    @Test
    void listAuditLogs_paginatesEqualTimestampsByIdWithoutGapsOrDuplicates() {
        UUID ticket = issuedTicket();
        body(ticketAction(adminToken, ticket, "reissue-qr", REASON_BODY), 200);
        ticketAction(adminToken, ticket, "resend", null).expectStatus().isEqualTo(202);
        body(ticketAction(adminToken, ticket, "void", REASON_BODY), 200);
        var expected = audits(ticket);
        assertThat(expected).hasSize(3);
        assertThat(expected.stream().map(AuditRow::at).distinct().toList()).containsExactly(catalog.now());
        List<String> ids = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < expected.size(); page++) {
            String json = cursor == null
                    ? body(get(adminToken, "/api/v1/admin/audit-logs?entity=ticket&entity_id={id}&limit=1", ticket), 200)
                    : body(get(adminToken, "/api/v1/admin/audit-logs?entity=ticket&entity_id={id}&limit=1&cursor={cursor}",
                            ticket, cursor), 200);
            assertThat(rows(json)).hasSize(1);
            ids.addAll(strings(json, "$.data[*].id"));
            assertThat(object(json)).containsKey("next_cursor");
            cursor = text(json, "$.next_cursor");
            if (page < expected.size() - 1) {
                assertThat(cursor).isNotBlank();
            } else {
                assertLastPage(json);
            }
        }

        assertThat(ids).doesNotHaveDuplicates()
                .containsExactlyElementsOf(expected.stream().map(row -> row.id().toString()).toList());
    }

    @Test
    void listAuditLogs_returnsEmptyLastPageForUnknownEntityId() {
        String json = body(get(adminToken,
                "/api/v1/admin/audit-logs?entity=ticket&entity_id={id}", UUID.randomUUID()), 200);

        assertThat(rows(json)).isEmpty();
        assertLastPage(json);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void listAuditLogs_returns400ForLimitOutsideOneToOneHundred(int limit) {
        problem(get(adminToken, "/api/v1/admin/audit-logs?limit={limit}", limit), 400, "VALIDATION_ERROR");
    }

    @Test
    void listAuditLogs_returns400ForMalformedCursor() {
        problem(get(adminToken, "/api/v1/admin/audit-logs?cursor=not-a-cursor"), 400, "VALIDATION_ERROR");
    }

    @Test
    void listAuditLogs_returns403ForBuyer() {
        problem(get(buyerToken, "/api/v1/admin/audit-logs"), 403, "FORBIDDEN");
    }

    @Test
    void listAuditLogs_returns401WithoutToken() {
        problem(get(null, "/api/v1/admin/audit-logs"), 401, "UNAUTHORIZED");
    }

    private List<Map<String, Object>> rows(String json) {
        return JsonPath.read(json, "$.data");
    }

    private void assertLastPage(String json) {
        assertThat(object(json)).containsEntry("next_cursor", null);
    }
}
