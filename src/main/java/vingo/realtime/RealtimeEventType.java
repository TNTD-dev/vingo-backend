package vingo.realtime;

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
