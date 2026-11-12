package vingo.catalog.web;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vingo.catalog.SeatBlockingService;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;

class AdminSeatBlockingIntegrationTest extends IntegrationTest {
    private final SeatBlockingService service;
    private final PlatformTransactionManager transactions;
    private String token;
    @Autowired
    AdminSeatBlockingIntegrationTest(SeatBlockingService service,PlatformTransactionManager transactions) {
        this.service=service; this.transactions=transactions;
    }
    @BeforeEach void prepare() { clock.set(Instant.parse("2026-11-12T09:00:00Z"));token=tokenFor(Role.ADMIN); }
    @AfterEach void reset() {clock.reset();}
    private RestTestClient.ResponseSpec post(UUID id,String action,Object body) {
        return client.post().uri("/api/v1/admin/showtimes/{id}/seats/{action}",id,action)
            .headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }
    private Object body(UUID... ids) {return Map.of("seat_ids",List.of(ids),"reason","Bảo trì");}
    private String state(UUID id,UUID seat) {
        return jdbc.sql("SELECT status FROM showtime_seat WHERE showtime_id=:id AND seat_id=:seat").param("id",id).param("seat",seat).query(String.class).single();
    }
    private long logs(UUID id) {return jdbc.sql("SELECT count(*) FROM catalog_seat_block_log WHERE showtime_id=:id").param("id",id).query(Long.class).single();}
    @Test void draftSeats_areIsolatedAndRepeatedRequestsAreNoops() {
        var event=catalog.event(EventSpec.defaults().withCity("HN").withStatus("DRAFT"));
        var first=catalog.showtime(event,Window.draft());var other=catalog.showtime(event,Window.draft());
        for(int i=0;i<2;i++) post(first.id(),"block",body(event.seat(1))).expectStatus().isOk();
        assertThat(state(first.id(),event.seat(1))).isEqualTo("BLOCKED");
        assertThat(state(other.id(),event.seat(1))).isEqualTo("AVAILABLE");assertThat(logs(first.id())).isEqualTo(1);
        for(int i=0;i<2;i++) post(first.id(),"unblock",body(event.seat(1))).expectStatus().isOk();
        assertThat(state(first.id(),event.seat(1))).isEqualTo("AVAILABLE");assertThat(logs(first.id())).isEqualTo(2);
    }
    @Test void heldAndSoldSeats_rejectWholeBatchForBothOperations() {
        for(String action:List.of("block","unblock")) for(String status:List.of("HELD","SOLD")) {
            var s=catalog.onSaleShowtime();var e=s.event();
            if(action.equals("unblock")) service.block(s.id(),List.of(e.seat(1)),"Bảo trì");
            UUID order=catalog.order(catalog.buyer(),s.id(),"PENDING",catalog.now().plusMinutes(10));
            catalog.setSeat(s.id(),e.seat(2),status,order,status.equals("HELD")?catalog.now().plusMinutes(10):null);
            long before=logs(s.id());
            post(s.id(),action,body(e.seat(1),e.seat(2))).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("SEAT_TAKEN");
            assertThat(state(s.id(),e.seat(1))).isEqualTo(action.equals("block")?"AVAILABLE":"BLOCKED");assertThat(logs(s.id())).isEqualTo(before);
        }
    }
    @Test void expiredHold_isAvailableOnlyStrictlyAfterExpiry() {
        var s=catalog.onSaleShowtime();UUID seat=s.event().seat(1);
        UUID order=catalog.order(catalog.buyer(),s.id(),"EXPIRED",catalog.now());
        catalog.setSeat(s.id(),seat,"HELD",order,catalog.now());
        post(s.id(),"block",body(seat)).expectStatus().isEqualTo(409);
        clock.set(clock.instant().plusSeconds(1));post(s.id(),"block",body(seat)).expectStatus().isOk();
        assertThat(state(s.id(),seat)).isEqualTo("BLOCKED");
        assertThat(jdbc.sql("SELECT order_id FROM showtime_seat WHERE showtime_id=:id AND seat_id=:seat").param("id",s.id()).param("seat",seat).query().singleRow().get("order_id")).isNull();
    }
    @Test void validationAndMissingShowtime_doNotChangeInventory() {
        var s=catalog.onSaleShowtime();var other=catalog.onSaleShowtime();UUID seat=s.event().seat(1);
        for(Object invalid:List.of(body(seat,seat),body(seat,other.event().seat(1)),Map.of("seat_ids",List.of(),"reason","x"),Map.of("seat_ids",List.of(seat),"reason"," "))) {
            post(s.id(),"block",invalid).expectStatus().isBadRequest();
            assertThat(state(s.id(),seat)).isEqualTo("AVAILABLE");assertThat(logs(s.id())).isZero();
        }
        for(String action:List.of("block","unblock")) post(UUID.randomUUID(),action,body(seat)).expectStatus().isNotFound();
    }
    @Test void closedSessionsAndEndedEvents_areLocked() {
        for(var window:List.of(Window.cancelled(),Window.finished())) {
            var e=catalog.event(EventSpec.defaults().withCity("HN"));var s=catalog.showtime(e,window);
            for(String action:List.of("block","unblock")) post(s.id(),action,body(e.seat(1))).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("SHOWTIME_LOCKED");
        }
        for(String status:List.of("ENDED","CANCELLED")) {
            var e=catalog.event(EventSpec.defaults().withCity("HN").withStatus(status));var s=catalog.showtime(e,Window.notOpenYet());
            for(String action:List.of("block","unblock")) post(s.id(),action,body(e.seat(1))).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("EVENT_LOCKED");
        }
    }
    @Test void rollback_restoresInventoryAndAudit() {
        var s=catalog.onSaleShowtime();UUID seat=s.event().seat(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{service.block(s.id(),List.of(seat),"Bảo trì");tx.setRollbackOnly();});
        assertThat(state(s.id(),seat)).isEqualTo("AVAILABLE");assertThat(logs(s.id())).isZero();
    }
    @Test void endpoints_requireAdmin() {
        var s=catalog.onSaleShowtime();String buyer=tokenFor(Role.BUYER);
        for(String action:List.of("block","unblock")) {
            client.post().uri("/api/v1/admin/showtimes/{id}/seats/{action}",s.id(),action).contentType(MediaType.APPLICATION_JSON).body(body(s.event().seat(1))).exchange().expectStatus().isUnauthorized();
            client.post().uri("/api/v1/admin/showtimes/{id}/seats/{action}",s.id(),action).headers(h->h.setBearerAuth(buyer)).contentType(MediaType.APPLICATION_JSON).body(body(s.event().seat(1))).exchange().expectStatus().isForbidden();
        }
    }
    @Test void seatmapReflectsBlockAndUnblockAfterCacheWarmup() {
        var session=catalog.onSaleShowtime();UUID seat=session.event().seat(1);
        String path="/api/v1/showtimes/"+session.id()+"/seatmap";
        client.get().uri(path).exchange().expectStatus().isOk();
        for(String action:List.of("block","unblock")) {
            post(session.id(),action,body(seat)).expectStatus().isOk();
            String json=client.get().uri(path).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
            boolean found=false;
            for(int i=0;i<root.path("zones").size();i++) {
                var seats=root.path("zones").get(i).path("seats");
                for(int j=0;j<seats.size();j++) if(seats.get(j).path("seat_id").asString().equals(seat.toString())) {
                    found=true;assertThat(seats.get(j).path("status").asString()).isEqualTo(action.equals("block")?"BLOCKED":"AVAILABLE");
                }
            }
            assertThat(found).isTrue();
        }
    }
    @Test
    @Timeout(60)
    void blockRacesWithBuyerHold_onlyOneWinsAndInventoryMatchesOrder() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            var session = catalog.onSaleShowtime();
            UUID seat = session.event().seat(1);
            UUID buyer = catalog.buyer();
            String buyerToken = tokenFor(buyer, Role.BUYER);
            var gate = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var block = pool.submit(() -> {
                    gate.await();
                    return post(session.id(), "block", body(seat))
                            .expectBody().returnResult().getStatus().value();
                });
                var hold = pool.submit(() -> {
                    gate.await();
                    return client.post().uri("/api/v1/showtimes/{id}/holds", session.id())
                            .headers(h -> {
                                h.setBearerAuth(buyerToken);
                                h.set("Idempotency-Key", UUID.randomUUID().toString());
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("seat_ids", List.of(seat))).exchange()
                            .expectBody().returnResult().getStatus().value();
                });
                gate.countDown();
                int blockStatus = block.get(20, java.util.concurrent.TimeUnit.SECONDS);
                int holdStatus = hold.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(List.of(blockStatus, holdStatus))
                        .isIn(List.of(200, 409), List.of(409, 201));
                var inventory = jdbc.sql("""
                        SELECT status, order_id FROM showtime_seat
                        WHERE showtime_id=:id AND seat_id=:seat
                        """).param("id", session.id()).param("seat", seat).query().singleRow();
                long orders = jdbc.sql("SELECT count(*) FROM orders WHERE showtime_id=:id")
                        .param("id", session.id()).query(Long.class).single();
                if (blockStatus == 200) {
                    assertThat(inventory.get("status")).isEqualTo("BLOCKED");
                    assertThat(inventory.get("order_id")).isNull();
                    assertThat(orders).isZero();
                    assertThat(logs(session.id())).isEqualTo(1);
                } else {
                    assertThat(inventory.get("status")).isEqualTo("HELD");
                    assertThat(inventory.get("order_id")).isNotNull();
                    assertThat(orders).isEqualTo(1);
                    assertThat(logs(session.id())).isZero();
                }
            }
        }
    }

    @Test void nullAndOversizedInputs_areRejectedForBothEndpoints() {
        var session = catalog.onSaleShowtime();
        for (String action : List.of("block", "unblock")) {
            for (Object ids : List.of(Arrays.asList((Object) null),
                    java.util.stream.IntStream.range(0, 201).mapToObj(n -> UUID.randomUUID()).toList())) {
                post(session.id(), action, Map.of("seat_ids", ids, "reason", "Maintenance"))
                        .expectStatus().isBadRequest();
            }
        }
        post(session.id(), "block", Map.of("seat_ids", List.of(session.event().seat(1))))
                .expectStatus().isBadRequest();
        post(session.id(), "block", Map.of("seat_ids", List.of(session.event().seat(1)), "reason", "x".repeat(1001)))
                .expectStatus().isBadRequest();
        assertThat(logs(session.id())).isZero();
    }
}
