package vingo.catalog;

import java.util.UUID;

public record PriceTierChange(
        UUID id,
        String name,
        Long price,
        String color
) {}
