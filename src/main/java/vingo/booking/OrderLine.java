package vingo.booking;

import java.util.UUID;

/** seatId null với khu đứng; ghế ngồi có quantity bằng 1. */
public record OrderLine(UUID orderItemId, UUID showtimeId, UUID zoneId, UUID seatId, int quantity) {}
