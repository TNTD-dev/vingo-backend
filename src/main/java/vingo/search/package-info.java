/**
 * Module search: Tìm sự kiện (gõ không dấu vẫn ra), sơ đồ ghế, số chỗ còn. Chủ: Đức.
 *
 * <p>Search không sở hữu bảng nào, chỉ đọc (JdbcClient): catalog gồm event, showtime, venue, hall, zone, seat,
 * price_tier, zone_price (chi tiết sự kiện đọc thêm hall, venue, price_tier); booking gồm showtime_seat, showtime_zone_stock.
 *
 * <p>Search phụ thuộc {@code catalog.ShowtimeStatus} (hàm thuần tính trạng thái suất, dùng chung với booking);
 * không import {@code catalog.internal} hay {@code catalog.web}.
 *
 * <p>Module khác chỉ được gọi class ở gốc package này. {@code web} và {@code internal} là nội bộ.
 */
@ApplicationModule(displayName = "search")
package vingo.search;

import org.springframework.modulith.ApplicationModule;
