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

    /** Phản hồi 200/201 sai JSON hoặc sai kiểu id được retry có giới hạn, không lộ body. */
    @Test
    void malformedSuccessResponses_areSanitizedRetryableFailures() throws Exception {
        var response=new AtomicReference<>("not-json-private-response");
        var status=new AtomicInteger(200);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var sender=ResendEmailSender.forLoopbackTest(new NotificationSettings("","test@example.test","test-key",
                    "http://127.0.0.1:"+server.getAddress().getPort()),CLOCK);
            for(int code:new int[]{200,201}) {
                status.set(code);
                for(String body:List.of("not-json-private-response","","null","{}","{\"id\":null}","{\"id\":123}","{\"id\":\" \"}")) {
                    response.set(body);
                    assertThatThrownBy(() -> sender.send(UUID.randomUUID(),new EmailPayload("buyer@example.test","test","body",List.of())))
                            .isInstanceOfSatisfying(DeliveryException.class,error -> {
                                assertThat(error.retryable()).isTrue();
                                assertThat(error.getMessage()).isEqualTo("INVALID_PROVIDER_RESPONSE");
                                assertThat(error.getCause()).isNull();
                            });
                }
            }
        } finally { server.stop(0); }
    }
    /** Header đã về nhưng body đứng vẫn phải hết hạn trước cửa sổ giữ thư 30 giây. */
    @Test
    void providerStalledBody_isBoundedByWholeRequestTimeout() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(15,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var sender=ResendEmailSender.forLoopbackTest(new NotificationSettings("","test@example.test","test-key",
                    "http://127.0.0.1:"+server.getAddress().getPort()),CLOCK);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(13), () ->
                assertThatThrownBy(() -> sender.send(UUID.randomUUID(),new EmailPayload("buyer@example.test","test","body",List.of())))
                    .isInstanceOfSatisfying(DeliveryException.class,error -> {
                        assertThat(error.retryable()).isTrue();
                        assertThat(error.code()).isEqualTo("EMAIL_TRANSPORT_ERROR");
                    }));
        } finally { release.countDown(); server.stop(0); }
    }

    /** Request provider phải giống từng byte dù cấu hình from đổi giữa hai tiến trình. */
    @Test
    void frozenSenderAndBody_areIdenticalAcrossSenderInstances() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        UUID key = UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Idempotency-Key") + "\n"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"same-provider-email\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var payload = new EmailPayload("buyer@example.test", "subject", "<p>fixed</p>", List.of(), "frozen@example.test");
            for (String from : List.of("first@example.test", "changed@example.test")) {
                var sender = ResendEmailSender.forLoopbackTest(new NotificationSettings("", from, "test-key",
                        "http://127.0.0.1:" + server.getAddress().getPort()), CLOCK);
                assertThat(sender.send(key, payload)).isEqualTo("same-provider-email");
            }
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).isEqualTo(requests.get(1)).contains("frozen@example.test").doesNotContain("changed@example.test");
        } finally {
            server.stop(0);
        }
    }

    /** Cấu hình HTTP phải bị chặn trước khi bất kỳ credential/body nào tới server. */
    @Test
    void deployedSender_rejectsHttpEvenOnLoopback_andTestExceptionCannotReachExternalHttp() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"id\":\"test-email\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var payload = new EmailPayload("buyer@example.test", "subject", "private html", List.of());
            String loopback = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String url : List.of(loopback, "http://api.resend.com", "ftp://api.resend.com",
                    "https://key@api.resend.com", "https://api.resend.com?x=1", "not a uri")) {
                var sender = new ResendEmailSender(new NotificationSettings("", "test@example.test", "private-key", url), CLOCK);
                assertThatThrownBy(() -> sender.send(UUID.randomUUID(), payload))
                        .isInstanceOfSatisfying(DeliveryException.class,
                                error -> assertThat(error.code()).isEqualTo("EMAIL_NOT_CONFIGURED"));
            }
            var external = ResendEmailSender.forLoopbackTest(
                    new NotificationSettings("", "test@example.test", "private-key", "http://example.test"), CLOCK);
            assertThatThrownBy(() -> external.send(UUID.randomUUID(), payload)).isInstanceOf(DeliveryException.class);
            assertThat(calls.get()).isZero();
            var testSender = ResendEmailSender.forLoopbackTest(
                    new NotificationSettings("", "test@example.test", "test-key", loopback), CLOCK);
            assertThat(testSender.send(UUID.randomUUID(), payload)).isEqualTo("test-email");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    /** Server giả xác nhận khóa gửi ổn định, Retry-After được giữ và lỗi được làm sạch. */
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
            var sender = ResendEmailSender.forLoopbackTest(new NotificationSettings("", "test@example.test", "test-key",
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

    /** Bao phủ ngày HTTP, số vượt long và header hỏng mà không dùng giờ thực của máy. */
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

    /** Mã JSON quyết định retry, không để message hoặc response hỏng giả làm lỗi đồng thời. */
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
            var sender = ResendEmailSender.forLoopbackTest(new NotificationSettings("", "test@example.test", "test-key",
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
            for (String empty : List.of("", "null")) {
                response.set(empty);
                assertThatThrownBy(() -> sender.send(key, payload)).isInstanceOfSatisfying(DeliveryException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_HTTP_409"));
            }
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
