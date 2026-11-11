package vingo.catalog;

import java.util.UUID;

/**
 * Sự kiện nội bộ: thông tin một sân (tên, địa chỉ, thành phố) vừa đổi.
 *
 * <p>Catalog phát event này trong transaction của use case sửa sân. Sân hiện trong chi tiết sự kiện và danh sách
 * suất của nhiều sự kiện nên search xoá toàn bộ cache event và suất sau khi transaction commit.
 *
 * @param venueId sân bị sửa
 */
public record VenueChanged(UUID venueId) {
}
