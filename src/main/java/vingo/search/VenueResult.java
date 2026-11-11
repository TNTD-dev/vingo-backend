package vingo.search;

import java.util.UUID;

/**
 * Địa điểm tổ chức. {@code type} là STADIUM, THEATER hoặc OUTDOOR. {@code address} và {@code city} có thể null;
 * {@code city} là mã thành phố lưu trong {@code venue.city}.
 */
public record VenueResult(UUID id, String name, String type, String address, String city) {
}
