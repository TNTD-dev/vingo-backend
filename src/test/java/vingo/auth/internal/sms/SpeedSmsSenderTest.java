package vingo.auth.internal.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SpeedSmsSenderTest {

    private static final String PHONE = "+84912345678";
    private static final String CODE = "482917";
    private static final String TOKEN = "test-access-token";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"status\":\"success\",\"code\":\"00\",\"data\":{\"tranId\":1}}";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/index.php";
    }

    private SmsProperties.Speedsms settings(String token, String smsType, String sender) {
        return new SmsProperties.Speedsms(baseUrl(), token, smsType, sender);
    }

    private SpeedSmsSender sender(String smsType) {
        return SpeedSmsSender.forLoopbackTest(settings(TOKEN, smsType, ""));
    }

    private Map<String, String> sentQuery() {
        var params = new LinkedHashMap<String, String>();
        for (String pair : rawQuery.get().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return params;
    }

    @Test
    void sendOtp_callsGetSendEndpointWithDocumentedParameters() {
        sender("4").sendOtp(UUID.randomUUID(), PHONE, CODE, 5);

        assertThat(method.get()).isEqualTo("GET");
        assertThat(path.get()).isEqualTo("/index.php/sms/send");
        assertThat(sentQuery()).containsEntry("access-token", TOKEN).containsEntry("to", "84912345678")
                .containsEntry("type", "4").doesNotContainKey("sender");
        assertThat(sentQuery().get("content")).contains(CODE).contains("5 phut");
    }

    @Test
    void sendOtp_ownBrandnameTypeSendsSender() {
        SpeedSmsSender.forLoopbackTest(settings(TOKEN, "3", "VINGO")).sendOtp(UUID.randomUUID(), PHONE, CODE, 5);

        assertThat(sentQuery()).containsEntry("type", "3").containsEntry("sender", "VINGO");
    }

    @Test
    void sendOtp_randomNumberTypeOmitsSender() {
        sender("2").sendOtp(UUID.randomUUID(), PHONE, CODE, 5);

        assertThat(sentQuery()).containsEntry("type", "2").doesNotContainKey("sender");
    }

    @Test
    void sendOtp_ownBrandnameTypeWithoutSenderIsRefusedWithoutCalling() {
        var misconfigured = SpeedSmsSender.forLoopbackTest(settings(TOKEN, "3", ""));

        assertThatThrownBy(() -> misconfigured.sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void sendOtp_unsupportedTypesAreRefusedWithoutCalling() {
        for (String type : new String[] {"1", "5", "9", ""}) {
            assertThatThrownBy(() -> sender(type).sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                    .isInstanceOf(SmsDeliveryException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void sendOtp_missingTokenIsRefusedWithoutCalling() {
        var noToken = SpeedSmsSender.forLoopbackTest(settings("", "4", ""));

        assertThatThrownBy(() -> noToken.sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void sendOtp_rejectsNonVietnamesePhoneWithoutCalling() {
        assertThatThrownBy(() -> sender("4").sendOtp(UUID.randomUUID(), "+14155550123", CODE, 5))
                .isInstanceOf(SmsDeliveryException.class).hasMessageNotContaining("4155550123");
        assertThat(calls.get()).isZero();
    }

    @Test
    void sendOtp_providerErrorBecomesExceptionWithoutLeakingSecrets() {
        responseBody = "{\"status\":\"error\",\"code\":\"300\",\"message\":\"Insufficient balance\"}";

        assertThatThrownBy(() -> sender("4").sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class).hasMessageContaining("300")
                .hasMessageNotContainingAny(PHONE, "912345678", CODE, TOKEN);
    }

    @Test
    void sendOtp_throwsWhenProviderReturnsHttpError() {
        status = 500;

        assertThatThrownBy(() -> sender("4").sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class).hasMessageContaining("500");
    }

    @Test
    void sendOtp_throwsWhenResponseBodyIsNotJson() {
        responseBody = "<html>oops</html>";

        assertThatThrownBy(() -> sender("4").sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    void sendOtp_transportFailureCarriesNeitherCauseNorSecrets() {
        var unreachable = SpeedSmsSender.forLoopbackTest(
                new SmsProperties.Speedsms("http://127.0.0.1:1/index.php", TOKEN, "4", ""));

        assertThatThrownBy(() -> unreachable.sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageNotContainingAny(PHONE, "912345678", CODE, TOKEN)
                .hasNoCause();
    }

    @Test
    void sendOtp_refusesPlainHttpEndpointInProductionConfiguration() {
        var insecure = new SpeedSmsSender(new SmsProperties("speedsms",
                new SmsProperties.Speedsms("http://api.speedsms.vn/index.php", TOKEN, "4", ""), false),
                new MockEnvironment());

        assertThatThrownBy(() -> insecure.sendOtp(UUID.randomUUID(), PHONE, CODE, 5))
                .isInstanceOf(SmsDeliveryException.class).hasMessageNotContaining(TOKEN);
    }

    @Test
    void sendOtp_logsCodeAndStillCallsProviderWhenLogCodeIsOn() {
        var appender = attach();
        try {
            SpeedSmsSender.forLoopbackTestLoggingCode(settings(TOKEN, "4", "")).sendOtp(UUID.randomUUID(), PHONE, CODE, 5);

            assertThat(calls.get()).isEqualTo(1);
            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message).contains(CODE).doesNotContain("912345678").doesNotContain(TOKEN);
        } finally {
            detach(appender);
        }
    }

    @Test
    void sendOtp_doesNotLogCodeByDefault() {
        var appender = attach();
        try {
            sender("4").sendOtp(UUID.randomUUID(), PHONE, CODE, 5);

            assertThat(appender.list).isEmpty();
        } finally {
            detach(appender);
        }
    }

    @Test
    void logCode_refusesToStartOutsideLocalAndTestProfiles() {
        var properties = new SmsProperties("speedsms", new SmsProperties.Speedsms("https://x", TOKEN, "4", ""), true);

        assertThatThrownBy(() -> new SpeedSmsSender(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        var api = new MockEnvironment();
        api.setActiveProfiles("api");
        assertThatThrownBy(() -> new SpeedSmsSender(properties, api)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logCode_allowedInLocalProfile() {
        var env = new MockEnvironment();
        env.setActiveProfiles("local");
        var properties = new SmsProperties("speedsms", new SmsProperties.Speedsms("https://x", TOKEN, "4", ""), true);

        new SpeedSmsSender(properties, env);
    }

    @Test
    void phoneMask_hidesLastThreeDigits() {
        assertThat(PhoneMask.mask(PHONE)).isEqualTo("+84912345***");
        assertThat(PhoneMask.mask(null)).isEqualTo("***");
    }

    private ListAppender<ILoggingEvent> attach() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(SpeedSmsSender.class)).addAppender(appender);
        return appender;
    }

    private void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(SpeedSmsSender.class)).detachAppender(appender);
    }
}
