package vingo.auth.internal.sms;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Gửi OTP qua API của SpeedSMS. Tài liệu SpeedSMS truyền token và nội dung trên URL (GET), nên URL không bao giờ
 * được log và lỗi truyền tải không đính kèm nguyên nhân: thông báo lỗi chỉ chứa mã kết quả hoặc tên loại lỗi,
 * không chứa số điện thoại, mã OTP hay token.
 */
@Component
@ConditionalOnProperty(name = "vingo.sms.provider", havingValue = "speedsms", matchIfMissing = true)
class SpeedSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SpeedSmsSender.class);
    private static final Pattern E164_VN = Pattern.compile("\\+84[35789][0-9]{8}");
    /** 2 = đầu số ngẫu nhiên, 3 = brandname riêng, 4 = brandname mặc định Verify/Notify. Không hỗ trợ 5 (app Android). */
    private static final Set<String> SMS_TYPES = Set.of("2", "3", "4");
    private static final String TYPE_OWN_BRANDNAME = "3";

    private final SmsProperties.Speedsms settings;
    private final boolean logCode;
    private final boolean allowLoopbackHttp;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    SpeedSmsSender(SmsProperties properties, Environment environment) {
        this(properties.speedsms(), properties.logCode(), false);
        if (properties.logCode() && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("vingo.sms.log-code chỉ dùng được với profile local hoặc test");
        }
    }

    private SpeedSmsSender(SmsProperties.Speedsms settings, boolean logCode, boolean allowLoopbackHttp) {
        this.settings = settings;
        this.logCode = logCode;
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    /** Chỉ test cùng package gọi; không có biến cấu hình triển khai để bật HTTP. */
    static SpeedSmsSender forLoopbackTest(SmsProperties.Speedsms settings) {
        return new SpeedSmsSender(settings, false, true);
    }

    /** Như {@link #forLoopbackTest(SmsProperties.Speedsms)} nhưng bật in mã OTP ra log, để test công tắc này. */
    static SpeedSmsSender forLoopbackTestLoggingCode(SmsProperties.Speedsms settings) {
        return new SpeedSmsSender(settings, true, true);
    }

    /** SpeedSMS không có tham số chặn gửi trùng nên {@code requestId} không dùng; giới hạn gửi nằm ở auth. */
    @Override
    public void sendOtp(UUID requestId, String phoneE164, String code, int validMinutes) {
        checkConfigured();
        if (phoneE164 == null || !E164_VN.matcher(phoneE164).matches()) {
            throw new SmsDeliveryException("Số điện thoại không phải số Việt Nam hợp lệ");
        }
        URI target;
        try {
            target = target(phoneE164, code, validMinutes);
        } catch (IllegalArgumentException ex) {
            // Không đính kèm cause: thông điệp của URISyntaxException có thể chứa cả URL (kèm token).
            throw new SmsDeliveryException("Không dựng được yêu cầu gửi SMS");
        }
        if (logCode) {
            // Ngoại lệ có chủ ý của quy tắc "không log OTP": chỉ để dev đọc mã khi thử luồng ở local/test.
            log.atWarn().addKeyValue("phone", PhoneMask.mask(phoneE164))
                    .log("DEV ONLY - mã OTP SMS: {} (hiệu lực {} phút)", code, validMinutes);
        }
        HttpResponse<String> response;
        try {
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).GET().build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SmsDeliveryException("Gửi SMS bị ngắt");
        } catch (java.io.IOException | RuntimeException ex) {
            // Không đính kèm cause: token nằm trên URL nên thông điệp của lỗi truyền tải có thể chứa nó.
            throw new SmsDeliveryException("Không gọi được nhà cung cấp SMS (" + ex.getClass().getSimpleName() + ")");
        }
        if (response.statusCode() / 100 != 2) {
            throw new SmsDeliveryException("Nhà cung cấp SMS trả HTTP " + response.statusCode()
                    + ", body=" + safeBody(response.body()));
        }
        Map<?, ?> result = parse(response.body());
        if (!"success".equals(result.get("status")) || !"00".equals(String.valueOf(result.get("code")))) {
            throw new SmsDeliveryException("Nhà cung cấp SMS từ chối, code=" + result.get("code")
                    + ", body=" + safeBody(response.body()));
        }
    }

    /** Mẫu không dấu để mỗi tin chỉ tính một phần; nên giữ nguyên chữ nếu sau này đăng ký mẫu với nhà mạng. */
    static String content(String code, int validMinutes) {
        return "Vingo: ma xac minh cua ban la " + code + ". Co hieu luc " + validMinutes
                + " phut. Khong chia se ma nay cho bat ky ai.";
    }

    private String safeBody(String body) {
        body = body == null ? "" : body;
        if (settings.accessToken() != null && !settings.accessToken().isBlank()) {
            body = body.replace(settings.accessToken(), "***");
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private Map<?, ?> parse(String responseBody) {
        try {
            // Thân "null" hợp lệ về JSON nhưng readValue trả null; coi như phản hồi không hiểu được.
            Map<?, ?> parsed = json.readValue(responseBody, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private URI target(String phoneE164, String code, int validMinutes) {
        // SpeedSMS nhận số dạng 84912345678 (không có dấu +).
        var query = new StringBuilder("?access-token=").append(enc(settings.accessToken()))
                .append("&to=").append(phoneE164.substring(1))
                .append("&content=").append(enc(content(code, validMinutes)))
                .append("&type=").append(settings.smsType());
        // Tài liệu SpeedSMS truyền sender ở mọi loại (loại 4: Verify hoặc Notify); loại 3 bắt buộc.
        if (TYPE_OWN_BRANDNAME.equals(settings.smsType())
                || (settings.sender() != null && !settings.sender().isBlank())) {
            query.append("&sender=").append(enc(settings.sender()));
        }
        return URI.create(base() + "/sms/send" + query);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Từ chối cấu hình thiếu hoặc không hợp lệ trước khi tạo request chứa secret. */
    private void checkConfigured() {
        boolean typeOk = SMS_TYPES.contains(settings.smsType())
                && (!TYPE_OWN_BRANDNAME.equals(settings.smsType()) || !settings.sender().isBlank());
        if (settings.accessToken().isBlank() || !typeOk) {
            throw new SmsDeliveryException("Chưa cấu hình nhà cung cấp SMS");
        }
        base();
    }

    /** Chỉ chấp nhận HTTPS (hoặc loopback trong test), không user-info, query hay fragment. */
    private String base() {
        try {
            URI base = URI.create(settings.baseUrl());
            boolean secure = "https".equalsIgnoreCase(base.getScheme());
            boolean loopbackTest = allowLoopbackHttp && "http".equalsIgnoreCase(base.getScheme())
                    && "127.0.0.1".equals(base.getHost());
            if ((!secure && !loopbackTest) || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return settings.baseUrl().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) {
            throw new SmsDeliveryException("Cấu hình nhà cung cấp SMS không hợp lệ");
        }
    }
}
