package vingo.catalog;

import java.util.UUID;

public record ZonePriceChange(
        UUID zoneId,
        UUID priceTierId
) {}