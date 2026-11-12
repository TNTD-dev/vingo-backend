package vingo.catalog;

import java.util.Set;
import java.util.UUID;

/**
 * Sự kiện nội bộ: dữ liệu catalog của một sự kiện vừa đổi.
 *
 * <p>Catalog phát event này (qua {@code ApplicationEventPublisher}) bên trong transaction của use case ghi của
 * admin: sửa, publish hoặc huỷ sự kiện, đổi giá, đổi {@code zone_price}, sửa suất, publish sơ đồ ghế. Search nghe
 * sau khi transaction commit để xoá cache, nên rollback thì không xoá oan.
 *
 * @param eventId sự kiện bị ảnh hưởng
 * @param showtimeIds các suất bị ảnh hưởng (rỗng nếu chỉ đổi thông tin chung của sự kiện)
 */
public record CatalogChanged(UUID eventId, Set<UUID> showtimeIds) {
}
