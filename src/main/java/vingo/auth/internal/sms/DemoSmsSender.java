package vingo.auth.internal.sms;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Chế độ demo ({@code vingo.sms.provider=demo}): không gửi SMS, mã OTP đi ngược về chính request xin mã
 * để controller trả trong header {@link #HEADER}, cho web hiện mã lên màn hình.
 *
 * <p>Ai gọi được API xin mã cũng đọc được mã, tức là xác minh số điện thoại chỉ còn là thủ tục. Chỉ bật cho
 * môi trường demo/thực tập không có dữ liệu thật; không đặt biến này ở production.
 */
@Component
@ConditionalOnProperty(name = "vingo.sms.provider", havingValue = "demo")
public class DemoSmsSender implements SmsSender {

    /** Header phản hồi chứa mã OTP của lần xin mã vừa rồi. */
    public static final String HEADER = "X-Demo-Otp";

    private static final String ATTRIBUTE = "vingo.sms.demoOtp";
    private static final Logger log = LoggerFactory.getLogger(DemoSmsSender.class);

    public DemoSmsSender() {
        log.warn("SMS_PROVIDER=demo: KHÔNG gửi SMS, mã OTP trả về trong header {}. Chỉ dùng cho demo, không có dữ liệu thật.",
                HEADER);
    }

    @Override
    public void sendOtp(UUID requestId, String phoneE164, String code, int validMinutes) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new SmsDeliveryException("Chế độ demo cần chạy trong một request HTTP");
        }
        attributes.setAttribute(ATTRIBUTE, code, RequestAttributes.SCOPE_REQUEST);
    }

    /** Lấy (và xoá) mã của request hiện tại; rỗng nếu không chạy chế độ demo. */
    public static Optional<String> takeCode() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object code = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        attributes.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable(code).map(Object::toString);
    }
}
