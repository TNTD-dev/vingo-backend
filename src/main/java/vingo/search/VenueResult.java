package vingo.search;

import java.util.UUID;

/**
 * Địa điểm tổ chức. {@code type} là STADIUM, THEATER hoặc OUTDOOR. {@code address} và {@code city} có thể null;
 * {@code city} là mã địa phương suy ra từ {@code venue.location}.
 */
public record VenueResult(UUID id, String name, String type, String address, String city) {
}
