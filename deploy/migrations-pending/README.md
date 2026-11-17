# Migration chờ triển khai

Các file ở đây chưa nằm trong `classpath:db/migration` nên Flyway không tự chạy.

`V20261116_1900__catalog_remove_hall.sql` chỉ chuyển quan hệ hall sang sân, chưa tạo schema SVG tổng quát/cổng/loại layout. Không áp dụng lên DB đang chạy backend/UI cũ còn dùng hall.

`V20261116_1910__catalog_venue_svg_v2.sql` chạy sau 1900: overview/gate/area, layout type và legacy, constraint/hash/bất biến PUBLISHED, import session/file/revision/quota/TTL. Chi tiết: `docs/venue-schema-v2.md`.

Kiểm tra SQL trên PostgreSQL 18 tạm, không chạm DB local:

```powershell
python scripts/check_remove_hall_migration.py
python scripts/check_venue_schema_migration.py
```

Runner cần Python và Docker, tự tạo và dọn một container riêng. Các SQL lịch sử được dùng để dựng schema trước chuyển đổi; dữ liệu kiểm tra là dữ liệu tự tạo.

`VenueSchemaMigrationTest` kiểm tra hai file chờ bằng Flyway thật ở schema Testcontainers riêng; migration chạy lại là no-op. Runner SQL kiểm tra sâu constraint, bản cũ/bản mới, nháp/quota và rollback.

Flyway mặc định commit mỗi file riêng. Lỗi ở file 1910 không tự hoàn tác file 1900 đã commit: cần chốt backup và cách triển khai tổng hợp/group trước khi áp dụng thực tế, không khởi động lại app cũ trên schema mới chuyển một phần.

Trước khi đưa file sang thư mục Flyway chính thức, hoàn tất API/backend/UI tương ứng, chốt lại version migration, sao lưu DB đích và thực hiện checklist tại `docs/remove-hall-plan.md`. SQL phải được chạy trong transaction. Không sửa migration lịch sử hoặc dùng CASCADE để bỏ dependency không rõ.
