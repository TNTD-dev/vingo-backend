package vingo.notifications.internal;

public class DeliveryException extends RuntimeException {
    private final boolean retryable;
    private final String code;
    private final int retryAfterSeconds;
    public DeliveryException(boolean retryable, String code, int retryAfterSeconds) {
        super(code); this.retryable = retryable; this.code = code; this.retryAfterSeconds = retryAfterSeconds;
    }
    public boolean retryable() { return retryable; }
    public String code() { return code; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
