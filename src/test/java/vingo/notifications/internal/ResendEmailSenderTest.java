package vingo.notifications.internal;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResendEmailSenderTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void provider_usesStableIdempotencyAndClassifiesErrors_withoutExposingResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var key = UUID.randomUUID();
        var status = new AtomicInteger(200);
        server.createContext("/emails", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(key.toString());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).contains("buyer@example.test");
            byte[] body = (status.get() == 200 ? "{\"id\":\"email-1\"}" : "{\"message\":\"secret provider body\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "7200");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var sender = new ResendEmailSender(new NotificationSettings("", "test@example.test", "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort()), CLOCK);
            var payload = new EmailPayload("buyer@example.test", "subject", "<p>test</p>", List.of());
            assertThat(sender.send(key, payload)).isEqualTo("email-1");
            status.set(429);
            assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class, exception -> {
                assertThat(exception.retryable()).isTrue();
                assertThat(exception.retryAfterSeconds()).isEqualTo(7200);
                assertThat(exception.getMessage()).isEqualTo("EMAIL_HTTP_429");
            });
            status.set(422);
            assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class,
                    exception -> assertThat(exception.retryable()).isFalse());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retryAfter_supportsSecondsDateAndInvalidHeaders_withInjectedClock() {
        assertThat(ResendEmailSender.retryDelay("7200", CLOCK)).isEqualTo(7200);
        String date = CLOCK.instant().plusSeconds(7200).atZone(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(ResendEmailSender.retryDelay(date, CLOCK)).isEqualTo(7200);
        assertThat(ResendEmailSender.retryDelay("invalid", CLOCK)).isZero();
        assertThat(ResendEmailSender.retryDelay("-10", CLOCK)).isZero();
        assertThat(ResendEmailSender.retryDelay("999999999999", CLOCK)).isEqualTo(Integer.MAX_VALUE);
        assertThat(ResendEmailSender.retryDelay("999999999999999999999999999999", CLOCK)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void provider_distinguishesConcurrentRequestsFromChangedPayload_andDoesNotTrustMessageText() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(409);
        var response = new AtomicReference<>("{\"name\":\"invalid_idempotent_request\",\"message\":\"private body\"}");
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var sender = new ResendEmailSender(new NotificationSettings("", "test@example.test", "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort()), CLOCK);
            var key = UUID.randomUUID();
            var payload = new EmailPayload("buyer@example.test", "subject", "<p>test</p>", List.of());
            assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class, error -> {
                assertThat(error.code()).isEqualTo("EMAIL_IDEMPOTENCY_CONFLICT");
                assertThat(error.retryable()).isFalse();
            });
            response.set("{\"name\":\"concurrent_idempotent_requests\"}");
            assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class,
                    error -> assertThat(error.retryable()).isTrue());
            response.set("{\"name\":\"invalid_idempotent_request\",\"message\":\"concurrent_idempotent_requests\"}");
            assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class,
                    error -> assertThat(error.retryable()).isFalse());
            response.set("not json: concurrent_idempotent_requests");
            assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class,
                    error -> assertThat(error.code()).isEqualTo("EMAIL_HTTP_409"));
            for (int code : new int[]{401, 403}) {
                status.set(code);
                assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_HTTP_" + code));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cipher_usesRandomNonceAuthenticatesPayload_andRequiresConfiguredKey() {
        var cipher = new PayloadCipher(new NotificationSettings("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "", "", ""));
        String first = vingo.notifications.LegacyPayloadFixture.encrypt("secret");
        assertThat(vingo.notifications.LegacyPayloadFixture.encrypt("secret")).isNotEqualTo(first);
        assertThat(cipher.decrypt(first)).isEqualTo("secret");
        assertThatThrownBy(() -> cipher.decrypt("bad")).isInstanceOf(IllegalStateException.class);
        var missing = new PayloadCipher(new NotificationSettings("", "", "", ""));
        assertThatThrownBy(() -> missing.decrypt(first)).isInstanceOf(IllegalStateException.class);
    }
}
