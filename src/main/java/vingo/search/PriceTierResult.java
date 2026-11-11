package vingo.search;

import java.util.UUID;

/** Một hạng giá của sự kiện. {@code price} là VND. {@code color} có thể null. */
public record PriceTierResult(UUID id, String name, long price, String color) {
}
