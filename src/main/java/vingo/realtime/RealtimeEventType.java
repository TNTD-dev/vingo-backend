package vingo.realtime;

/**
 * Loại sự kiện cho {@link RealtimeService#publish}; giá trị trong ngoặc là chuỗi lưu ở
 * {@code realtime_event.type}: {@code order.held}, {@code order.paid}, {@code order.expired},
 * {@code order.needs_review}, {@code payment.failed}, {@code ticket.issued},
 * {@code chat.delta}, {@code chat.block}, {@code chat.done}.
 * Người gọi cung cấp payload theo từng loại sự kiện; tên hằng enum không phải giá trị DB.
 */
public enum RealtimeEventType {
    ORDER_HELD("order.held"),
    ORDER_PAID("order.paid"),
    ORDER_EXPIRED("order.expired"),
    ORDER_NEEDS_REVIEW("order.needs_review"),
    PAYMENT_FAILED("payment.failed"),
    TICKET_ISSUED("ticket.issued"),
    CHAT_DELTA("chat.delta"),
    CHAT_BLOCK("chat.block"),
    CHAT_DONE("chat.done");

    private final String dbValue;

    RealtimeEventType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
