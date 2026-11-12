package vingo.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.JwtProperties;
import vingo.shared.security.Role;
import vingo.shared.security.SecurityConfig;
import vingo.support.IntegrationTest;

class RealtimeStreamTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofSeconds(1);

    private final RealtimeService realtime;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final JwtProperties jwt;
    private final int port;
    private final List<SseTestClient> streams = new ArrayList<>();

    @Autowired
    RealtimeStreamTest(RealtimeService realtime, PlatformTransactionManager transactionManager,
                       JsonMapper json, JwtProperties jwt, @Value("${local.server.port}") int port) {
        this.realtime = realtime;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.jwt = jwt;
        this.port = port;
    }

    @AfterEach
    void closeStreamsAndResetClock() {
        try {
            assertAll(streams.stream().map(stream -> (Executable) stream::close));
        } finally {
            clock.reset();
        }
    }

    @Test
    void stream_returns401_whenTokenIsMissing() {
        client.get().uri("/api/v1/me/stream")
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void stream_sendsCommittedEventWithPayloadAndStreamingHeaders() throws Exception {
        UUID user = catalog.buyer();
        var stream = connect(user, null);
        Map<String, Object> payload = Map.of("order_id", UUID.randomUUID(), "quantity", 2,
                "message", "Giữ vé\nHàng A", "seats", List.of("A1", "A2"));

        UUID id = publish(user, payload);

        var event = stream.awaitEvent(WAIT);
        assertThat(event.id()).isEqualTo(id);
        assertThat(event.event()).isEqualTo("order.held");
        assertThat(json.readTree(event.data())).isEqualTo(json.readTree(json.writeValueAsString(payload)));
        assertThat(stream.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(stream.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(stream.headers().firstValue("X-Accel-Buffering")).contains("no");
        stream.awaitNoEvent(QUIET);
    }

    @Test
    void stream_onlyDeliversEventsToTheirOwner() throws Exception {
        UUID userA = catalog.buyer();
        UUID userB = catalog.buyer();
        var streamA = connect(userA, null);
        var streamB = connect(userB, null);

        UUID eventA = publish(userA);
        assertThat(streamA.awaitEvent(WAIT).id()).isEqualTo(eventA);
        streamB.awaitNoEvent(QUIET);

        UUID eventB = publish(userB);
        assertThat(streamB.awaitEvent(WAIT).id()).isEqualTo(eventB);
        streamA.awaitNoEvent(QUIET);
    }

    @Test
    void stream_doesNotSendRolledBackEvents() throws Exception {
        UUID user = catalog.buyer();
        var stream = connect(user, null);

        UUID rolledBack = transactions.execute(status -> {
            UUID id = realtime.publish(user, RealtimeEventType.ORDER_HELD, Map.of());
            status.setRollbackOnly();
            return id;
        });

        assertThat(jdbc.sql("SELECT count(*) FROM realtime_event WHERE id = :id")
                .param("id", rolledBack).query(Integer.class).single()).isZero();
        stream.awaitNoEvent(QUIET);
        UUID committed = publish(user);
        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(committed);
    }

    @Test
    void reconnect_replaysMissedEventsInIdOrderWithoutCursorOrOtherUsers() throws Exception {
        UUID user = catalog.buyer();
        var original = connect(user, null);
        UUID first = publish(user);
        assertThat(original.awaitEvent(WAIT).id()).isEqualTo(first);
        original.close();

        UUID second = publish(user);
        UUID foreign = publish(catalog.buyer());
        UUID third = publish(user);
        var resumed = connect(user, first.toString());

        var received = List.of(resumed.awaitEvent(WAIT).id(), resumed.awaitEvent(WAIT).id());
        assertThat(received).contains(second, third).containsSubsequence(second, third)
                .doesNotContain(first, foreign);
        resumed.awaitNoEvent(QUIET);
    }

    @Test
    void reconnect_usesOverlapWhenEarlierIdCommitsAfterLastReceivedId() throws Exception {
        UUID user = catalog.buyer();
        var original = connect(user, null);
        var inserted = new CompletableFuture<UUID>();
        var committed = new CompletableFuture<UUID>();
        var allowCommit = new CountDownLatch(1);
        Thread firstTransaction = Thread.ofVirtual().start(() -> {
            try {
                UUID id = transactions.execute(status -> {
                    UUID first = realtime.publish(user, RealtimeEventType.ORDER_HELD, Map.of("sequence", 1));
                    inserted.complete(first);
                    try {
                        assertThat(allowCommit.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    return first;
                });
                committed.complete(id);
            } catch (Throwable ex) {
                inserted.completeExceptionally(ex);
                committed.completeExceptionally(ex);
            }
        });
        try {
            UUID first = inserted.get(5, TimeUnit.SECONDS);
            UUID second = publish(user, Map.of("sequence", 2));
            assertThat(first.toString()).isLessThan(second.toString());
            assertThat(original.awaitEvent(WAIT).id()).isEqualTo(second);
            original.close();
            allowCommit.countDown();
            assertThat(committed.get(5, TimeUnit.SECONDS)).isEqualTo(first);

            var resumed = connect(user, second.toString());
            assertThat(resumed.awaitEvent(WAIT).id()).isEqualTo(first);
            resumed.awaitNoEvent(QUIET);
        } finally {
            allowCommit.countDown();
            assertThat(firstTransaction.join(WAIT)).isTrue();
        }
    }

    @Test
    void reconnect_withExpiredCursorReplaysOnlyCurrentFiveMinuteWindow() throws Exception {
        UUID user = catalog.buyer();
        var probe = connect(user, null);
        UUID old = publish(user);
        clock.advance(Duration.ofMinutes(6));
        UUID recent = publish(user);
        awaitDelivered(probe, old, recent);

        var stream = connect(user, old.toString());

        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(recent).isNotEqualTo(old);
        stream.awaitNoEvent(QUIET);
    }

    @Test
    void reconnect_withMalformedCursorReplaysOwnWindowWithoutError() throws Exception {
        assertFallbackReplay("abc");
    }

    @Test
    void reconnect_withUnknownUuidReplaysOwnWindowWithoutError() throws Exception {
        assertFallbackReplay(UUID.randomUUID().toString());
    }

    @Test
    void reconnect_withAnotherUsersCursorReplaysOnlyOwnEvents() throws Exception {
        UUID user = catalog.buyer();
        UUID own = publish(user);
        UUID foreign = publish(catalog.buyer());

        var stream = connect(user, foreign.toString());

        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(own).isNotEqualTo(foreign);
        stream.awaitNoEvent(QUIET);
    }

    @Test
    void stream_withoutCursorDoesNotReplayExistingEvents() throws Exception {
        UUID user = catalog.buyer();
        var probe = connect(user, null);
        awaitDelivered(probe, publish(user));

        var stream = connect(user, null);

        stream.awaitNoEvent(QUIET);
    }

    @Test
    void stream_closesOldestConnectionWhenSixthOpens() throws Exception {
        UUID user = catalog.buyer();
        List<SseTestClient> connections = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            connections.add(connect(user, null));
        }

        connections.getFirst().awaitClosed(WAIT);
        UUID id = publish(user);

        for (SseTestClient stream : connections.subList(1, 6)) {
            assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(id);
        }
    }

    @Test
    void stream_sendsPingCommentsAtConfiguredHeartbeat() throws Exception {
        var stream = connect(catalog.buyer(), null);

        stream.awaitComment("ping", WAIT);
        stream.awaitComment("ping", WAIT);
        stream.awaitNoEvent(QUIET);
    }

    @Test
    void stream_closesWhenJwtExpires() throws Exception {
        UUID user = catalog.buyer();
        Instant expiresAt = clock.instant().plusSeconds(4).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var stream = connectWithToken(tokenExpiringAt(user, expiresAt), null);
        assertThat(clock.instant()).isBefore(expiresAt);
        UUID id = publish(user);
        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(id);

        stream.awaitClosed(WAIT);

        assertThat(clock.instant()).isAfterOrEqualTo(expiresAt);
    }

    @Test
    void stream_catchesUpAfterListenerConnectionIsTerminated() throws Exception {
        UUID user = catalog.buyer();
        var stream = connect(user, null);
        UUID first = publish(user);
        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(first);

        List<Boolean> terminated = jdbc.sql("""
                        SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                        WHERE application_name = 'vingo-realtime-listener'
                        """).query(Boolean.class).list();
        assertThat(terminated).isNotEmpty().allMatch(Boolean.TRUE::equals);
        UUID missed = publish(user);

        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(missed);
        stream.awaitNoEvent(QUIET);
    }

    @Test
    void stream_catchUpWithoutSentEventsIncludesTransactionStartedJustBeforeConnect() throws Exception {
        UUID user = catalog.buyer();
        Instant connectedAt = clock.instant();
        clock.set(connectedAt);
        var stream = connect(user, null);

        terminateListener();
        // Transaction bắt đầu 5s trước khi mở stream (created_at sớm) nhưng commit khi listener đang mất kết nối.
        clock.set(connectedAt.minusSeconds(5));
        UUID lateCommit = publish(user);
        clock.set(connectedAt);

        assertThat(stream.awaitEvent(WAIT).id()).isEqualTo(lateCommit);
        stream.awaitNoEvent(QUIET);
    }

    private void terminateListener() {
        List<Boolean> terminated = jdbc.sql("""
                        SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                        WHERE application_name = 'vingo-realtime-listener'
                        """).query(Boolean.class).list();
        assertThat(terminated).isNotEmpty().allMatch(Boolean.TRUE::equals);
    }

    private void assertFallbackReplay(String cursor) throws Exception {
        UUID user = catalog.buyer();
        UUID first = publish(user);
        UUID foreign = publish(catalog.buyer());
        UUID second = publish(user);

        var stream = connect(user, cursor);

        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(List.of(stream.awaitEvent(WAIT).id(), stream.awaitEvent(WAIT).id()))
                .containsExactly(first, second).doesNotContain(foreign);
        stream.awaitNoEvent(QUIET);
    }

    // Chờ NOTIFY của sự kiện cũ được xử lý xong, để stream mở sau không nhận nó qua đường live.
    private static void awaitDelivered(SseTestClient probe, UUID... ids) throws Exception {
        for (UUID id : ids) {
            assertThat(probe.awaitEvent(WAIT).id()).isEqualTo(id);
        }
        probe.close();
    }

    private SseTestClient connect(UUID user, String lastEventId) throws Exception {
        return connectWithToken(tokenFor(user, Role.BUYER), lastEventId);
    }

    private SseTestClient connectWithToken(String token, String lastEventId) throws Exception {
        var stream = new SseTestClient(port, token, lastEventId);
        streams.add(stream);
        return stream;
    }

    private UUID publish(UUID user) {
        return publish(user, Map.of("order_id", UUID.randomUUID()));
    }

    private UUID publish(UUID user, Map<String, Object> payload) {
        return transactions.execute(status -> realtime.publish(user, RealtimeEventType.ORDER_HELD, payload));
    }

    private String tokenExpiringAt(UUID user, Instant expiresAt) {
        var encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(jwt)).build();
        var claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer()).subject(user.toString())
                .claim(CurrentUser.ROLE_CLAIM, Role.BUYER.name())
                .issuedAt(clock.instant()).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
