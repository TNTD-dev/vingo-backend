package vingo.auth.internal.sms;

import java.util.UUID;

/** Cổng gửi SMS OTP; bản cài đặt gọi nhà cung cấp, test thay bằng bản giả. */
public interface SmsSender {

    /**
     * Gửi mã OTP tới số E.164 (+84...). Gọi ngoài transaction.
     *
     * @param requestId id duy nhất cho lần gửi, để nhà cung cấp chặn gửi trùng
     * @throws SmsDeliveryException nếu nhà cung cấp từ chối hoặc không gọi được
     */
    void sendOtp(UUID requestId, String phoneE164, String code, int validMinutes);
}
