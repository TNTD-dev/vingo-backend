/**
 * Module search: Tìm sự kiện (gõ không dấu vẫn ra), sơ đồ ghế, số chỗ còn. Chủ: Đức.
 *
 * <p>Search không sở hữu bảng nào, chỉ đọc (JdbcClient): catalog gồm event, showtime, venue, hall, zone, seat,
 * price_tier, zone_price; booking gồm showtime_seat, showtime_zone_stock.
 *
 * <p>Module khác chỉ được gọi class ở gốc package này. {@code web} và {@code internal} là nội bộ.
 */
@ApplicationModule(displayName = "search")
package vingo.search;

import org.springframework.modulith.ApplicationModule;
