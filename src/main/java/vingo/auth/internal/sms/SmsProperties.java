package vingo.auth.internal.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình gửi SMS OTP. {@code provider} là {@code speedsms} (gọi nhà cung cấp, mặc định) hoặc {@code log}
 * (chỉ cho profile local/test, không gọi ra ngoài). Token lấy từ biến môi trường, không có giá trị mặc định chứa secret.
 * {@code logCode} in thêm mã OTP ra log khi vẫn gọi nhà cung cấp; chỉ chạy được ở profile local/test.
 */
@ConfigurationProperties("vingo.sms")
public record SmsProperties(
        @DefaultValue("speedsms") String provider,
        @DefaultValue Speedsms speedsms,
        @DefaultValue("false") boolean logCode) {

    /**
     * {@code smsType} theo SpeedSMS: 2 = đầu số ngẫu nhiên, 3 = brandname riêng (cần {@code sender}),
     * 4 = brandname mặc định Verify/Notify. SpeedSMS không có chế độ thử: gọi thật là gửi thật và tính tiền.
     */
    public record Speedsms(
            @DefaultValue("https://api.speedsms.vn/index.php") String baseUrl,
            @DefaultValue("") String accessToken,
            @DefaultValue("4") String smsType,
            @DefaultValue("") String sender) {
    }
}
