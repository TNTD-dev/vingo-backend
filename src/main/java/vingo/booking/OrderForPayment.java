package vingo.booking;

import java.time.OffsetDateTime;
import java.util.UUID;

/** status là giá trị DB: PENDING, PAID, EXPIRED, CANCELLED, NEEDS_REVIEW, REFUNDED. */
public record OrderForPayment(UUID id, UUID userId, UUID showtimeId, String status,
                              long totalAmount, OffsetDateTime expiresAt) {}
