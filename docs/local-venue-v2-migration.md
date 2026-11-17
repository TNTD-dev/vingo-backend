# Migrate DB local sang sân v2 — 16/11/2026

Đã thực hiện theo yêu cầu migrate local, lúc 23:06 (Asia/Bangkok). Chỉ thay đổi DB `vingo` tại `localhost:5432`, PostgreSQL 18.6, container `vingo-backend-postgres-1`, volume `vingo-backend_pgdata`. Không triển khai staging/production hoặc UI.

## Migration đã áp dụng

Flyway chạy ba migration trong một transaction (`group=true`), từ phiên bản `20261113.1600` lên `20261116.1910`:

1. `20261116.1800`: trường khóa quản trị/phiên bản quyền tài khoản và action audit quản lý user.
2. `20261116.1900`: bỏ hall, chuyển layout trực thuộc venue, bỏ hall_id của showtime.
3. `20261116.1910`: schema overview/gate/area, loại layout v2, mapping, validation và import session/file.

Flyway validate đạt với 24 migration. Chạy lại migrate không có thay đổi. Không repair hoặc tự ghi tay lịch sử Flyway.

## Sao lưu và đối chiếu

Backup custom-format PostgreSQL 18 lưu trong thư mục local được gitignore:

`.local-handoff/backups/vingo-before-venue-v2-20261116-230428.dump`

- Kích thước: 2.046.520 byte.
- SHA-256: `573b055dd2bf5999baee7c93e1038c168f2be7355d1d6f680c9aebc22f18a4ea`.
- `pg_restore --list` đọc archive thành công; có file TOC cùng thư mục.
- Snapshot trước/sau: `.local-handoff/local-venue-before.json` và `local-venue-after.json`.

Đã đối chiếu SHA-256 nội dung hàng của toàn bộ bảng nghiệp vụ cũ, trừ các cột thay đổi có chủ đích, cùng fingerprint mapping layout/showtime → venue. Tất cả khớp; không chỉ so sánh số lượng.

| Dữ liệu | Trước | Sau |
|---|---:|---:|
| Sân | 8 | 8 |
| Layout | 7 | 7 |
| Zone | 94 | 94 |
| Ghế | 36.586 | 36.586 |
| Sự kiện | 27 | 27 |
| Showtime | 32 | 32 |
| Tồn ghế theo showtime | 1.052 | 1.052 |
| Tồn khu đứng theo showtime | 34 | 34 |
| Đơn hàng | 12 | 12 |
| Chi tiết đơn | 133 | 133 |
| Tài khoản | 7 | 7 |

Hall đã được loại bỏ. Cả 7 layout cũ giữ nguyên ID/status/version/SVG/ghế, được đánh dấu legacy schema 1; layout_type và overview_id chưa được suy đoán/gán tự động. Năm bảng overview/area/gate/import_session/import_file đã có và đang rỗng. Không có constraint chưa validate trong schema public.

## Cấu hình local và xem trên DBeaver

> **Cập nhật 17/11/2026:** các migration sân v2 đã chuyển vào `src/main/resources/db/migration`, thư mục `deploy/migrations-pending` đã bỏ. Không cần dòng cấu hình dưới đây nữa; xoá khỏi `.env.local` nếu còn. Phần còn lại giữ làm biên bản.

Tại thời điểm migrate, hai SQL còn nằm trong `deploy/migrations-pending`, chưa chuyển vào Flyway mặc định cho mọi môi trường. Đã bổ sung riêng vào `.env.local` (gitignore):

```properties
spring.flyway.locations=classpath:db/migration,filesystem:deploy/migrations-pending
```

Đã kiểm tra Spring Boot với profile local đọc đúng cấu hình này và datasource trùng DB đã migrate. Khi chạy từ thư mục repo, `api,local` sẽ nhận đủ lịch sử migration, không báo migration applied-but-unresolved. Worker cần dùng cùng schema mới.

Trên DBeaver, kết nối `localhost:5432/vingo`, refresh schema `public → Tables` (F5; reconnect nếu còn cache). Quan hệ mới là `seat_layout.venue_id → venue.id`; showtime chỉ giữ seat_layout_id. Mở lại ERD để thay sơ đồ có hall cũ.

Layout legacy vẫn phục vụ dữ liệu/suất cũ. Để gắn layout vào showtime mới, cần import và phát hành layout v2; dùng `include_legacy=true` khi cần xem layout lịch sử qua API quản trị. Không chạy lại `scripts/seed.sql` của schema hall cũ.

Backend/UI chưa được khởi động lại trong bước migrate này. UI wizard thuộc mục 1.4; kiểm tra toàn ứng dụng và triển khai môi trường khác vẫn thuộc mục 1.5. Lỗi compile có sẵn ở phần Google Places/Gemini vẫn được giữ nguyên, không liên quan kết quả migration DB.
