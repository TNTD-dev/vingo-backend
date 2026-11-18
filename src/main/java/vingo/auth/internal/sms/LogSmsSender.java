package vingo.auth.internal.sms;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Bản dev: không gửi SMS, chỉ in mã OTP ra log để thử luồng trên máy local. Khởi động sẽ lỗi nếu
 * không chạy profile local hoặc test, nên không thể vô tình dùng ở staging hay production.
 */
@Component
@ConditionalOnProperty(name = "vingo.sms.provider", havingValue = "log")
class LogSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    LogSmsSender(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("vingo.sms.provider=log chỉ dùng được với profile local hoặc test");
        }
    }

    @Override
    public void sendOtp(UUID requestId, String phoneE164, String code, int validMinutes) {
        // Ngoại lệ có chủ ý của quy tắc "không log OTP": chỉ để dev đọc mã khi không có SMS thật.
        log.atWarn().addKeyValue("phone", PhoneMask.mask(phoneE164))
                .log("DEV ONLY - mã OTP SMS: {} (hiệu lực {} phút)", code, validMinutes);
    }
}
