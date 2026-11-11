package vingo.notifications.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ResendEmailSender implements EmailSender {
    private final NotificationSettings settings;
    private final java.time.Clock clock;
    private final boolean allowLoopbackHttp;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired
    public ResendEmailSender(NotificationSettings settings, java.time.Clock clock) { this(settings, clock, false); }

    private ResendEmailSender(NotificationSettings settings, java.time.Clock clock, boolean allowLoopbackHttp) {
        this.settings = settings;
        this.clock = clock;
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    /** Chỉ test cùng package gọi; không có biến cấu hình triển khai để bật HTTP. */
    static ResendEmailSender forLoopbackTest(NotificationSettings settings, java.time.Clock clock) {
        return new ResendEmailSender(settings, clock, true);
    }

    /** Từ chối URL không an toàn trước khi tạo request chứa secret; không log URL. */
    private URI endpoint() {
        if (settings.apiKey().isBlank()) throw new DeliveryException(false, "EMAIL_NOT_CONFIGURED", 0);
        try {
            URI base = URI.create(settings.baseUrl());
            boolean secure = "https".equalsIgnoreCase(base.getScheme());
            boolean loopbackTest = allowLoopbackHttp && "http".equalsIgnoreCase(base.getScheme())
                    && ("127.0.0.1".equals(base.getHost()) || "[::1]".equals(base.getHost()));
            if ((!secure && !loopbackTest) || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return URI.create(settings.baseUrl().replaceAll("/+$", "") + "/emails");
        } catch (IllegalArgumentException exception) {
            throw new DeliveryException(false, "EMAIL_NOT_CONFIGURED", 0);
        }
    }
    /**
     * Gửi bằng outbox UUID làm idempotency key, không log nội dung thư/provider.
     * Phân biệt yêu cầu đồng thời có thể thử lại với nội dung đã đổi cần đối soát.
     *
     * @return id email được provider chấp nhận, chưa phải xác nhận tới hộp thư
     * @throws DeliveryException lỗi provider hoặc transport đã được làm sạch
     */
    @Override public String send(UUID key, EmailPayload payload) {
        URI target = endpoint();
        var body = new LinkedHashMap<String, Object>();
        body.put("from", payload.sender() == null ? settings.sender() : payload.sender()); body.put("to", List.of(payload.recipient()));
        body.put("subject", payload.subject()); body.put("html", payload.html());
        if (!payload.attachments().isEmpty()) body.put("attachments", payload.attachments());
        try {
            var request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + settings.apiKey())
                .header("Idempotency-Key", key.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try {
                // Bao gồm cả đọc body, không chỉ đợi header phản hồi.
                response = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException exception) {
                pending.cancel(true);
                throw new DeliveryException(true, "EMAIL_TRANSPORT_ERROR", 0);
            } catch (InterruptedException exception) {
                pending.cancel(true);
                throw exception;
            }
            int status = response.statusCode();
            if (status == 200 || status == 201) {
                return acceptedId(response.body());
            }
            String errorName = status == 409 ? providerErrorName(response.body()) : "";
            boolean retry = status == 429 || status >= 500 || (status == 409
                && errorName.equals("concurrent_idempotent_requests"));
            int delay = response.headers().firstValue("Retry-After").map(value -> retryDelay(value, clock)).orElse(0);
            if (status == 409 && errorName.equals("invalid_idempotent_request")) {
                throw new DeliveryException(false, "EMAIL_IDEMPOTENCY_CONFLICT", delay);
            }
            throw new DeliveryException(retry, "EMAIL_HTTP_" + status, delay);
        } catch (DeliveryException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new DeliveryException(true, "EMAIL_INTERRUPTED", 0);
        } catch (java.util.concurrent.ExecutionException e) { throw new DeliveryException(true, "EMAIL_TRANSPORT_ERROR", 0); }
    }
    @Override public void validateConfiguration() { endpoint(); }

    /** Body thành công hỏng/thiếu id phải tiêu hao retry, không pause vô hạn hoặc log body. */
    private String acceptedId(String body) {
        try {
            var response = json.readTree(body);
            if (response == null) throw new DeliveryException(true, "INVALID_PROVIDER_RESPONSE", 0);
            var id = response.path("id");
            if (!id.isString() || id.asText().isBlank()) throw new DeliveryException(true, "INVALID_PROVIDER_RESPONSE", 0);
            return id.asText();
        } catch (tools.jackson.core.JacksonException error) {
            throw new DeliveryException(true, "INVALID_PROVIDER_RESPONSE", 0);
        }
    }
    /** Chỉ đọc mã lỗi có cấu trúc, không dùng nội dung message hoặc ghi response vào log. */
    private String providerErrorName(String body) {
        try {
            var response = json.readTree(body);
            return response == null ? "" : response.path("name").asText();
        }
        catch (tools.jackson.core.JacksonException ignored) { return ""; }
    }

    /**
     * Giữ thời gian chờ provider yêu cầu, kể cả trên 24 giờ; không áp hạn của
     * idempotency key vào Retry-After. Số vượt long vẫn bão hoà thay vì trở về 0.
     */
    static int retryDelay(String value, java.time.Clock clock) {
        try { return new java.math.BigInteger(value.trim()).max(java.math.BigInteger.ZERO)
                .min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).intValue(); }
        catch (NumberFormatException ignored) {
            try {
                var date=java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                long millis=Duration.between(clock.instant(),date.toInstant()).toMillis();
                return (int)Math.min(Integer.MAX_VALUE,Math.max(0,(millis+999)/1000));
            } catch (java.time.DateTimeException e) { return 0; }
        }
    }
}
