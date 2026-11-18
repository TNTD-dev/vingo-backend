package vingo.auth.internal.sms;

/** Nhà cung cấp SMS từ chối hoặc không phản hồi; thông điệp không chứa số điện thoại hay mã OTP. */
public class SmsDeliveryException extends RuntimeException {

    public SmsDeliveryException(String message) {
        super(message);
    }

    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
