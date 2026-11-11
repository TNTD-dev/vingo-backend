package vingo.notifications.internal;

/** Key sai và ciphertext hỏng đều phải giữ nội dung để kiểm tra/phục hồi. */
public class PayloadUnavailableException extends IllegalStateException {
    public PayloadUnavailableException() { super("Notification payload unavailable; configuration or data needs review"); }
}
