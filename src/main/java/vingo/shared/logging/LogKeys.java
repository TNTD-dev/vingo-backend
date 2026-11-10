package vingo.shared.logging;

/**
 * Tên khoá MDC dùng chung. Thêm id nghiệp vụ vào log bằng {@code MDC.putCloseable(LogKeys.ORDER_ID, ...)}.
 */
public final class LogKeys {

    public static final String REQUEST_ID = RequestIdFilter.MDC_KEY;
    public static final String USER_ID = "user_id";
    public static final String ORDER_ID = "order_id";
    public static final String CONVERSATION_ID = "conversation_id";

    private LogKeys() {
    }
}
