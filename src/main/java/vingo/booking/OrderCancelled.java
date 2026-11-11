package vingo.booking;

import java.util.UUID;

/** Sự kiện đồng bộ trong transaction huỷ đơn để payment đóng intent OPEN. */
public record OrderCancelled(UUID orderId, UUID userId) {}
