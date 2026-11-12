# CP6 · Công bố sơ đồ sân và gắn vào suất diễn

Admin nạp SVG vào một `hall` để tạo sơ đồ `DRAFT`, duyệt sơ đồ, rồi công bố phiên bản muốn dùng. Khi tạo suất, admin gửi `hall_id` và `seat_layout_id` đã chọn. Suất giữ ID phiên bản cụ thể, nên khi sân công bố bản mới, suất đã gắn bản cũ vẫn sử dụng sơ đồ cũ.

Phần này tiếp nối [thiết kế contract và schema CP2](venue-management-cp2.md). Chi tiết request và response nằm trong [OpenAPI](../contracts/vingo-openapi.yaml).

## Luồng API

Các đường dẫn bên dưới có tiền tố `/api/v1` và yêu cầu quyền `ADMIN`.

| Bước | API | Kết quả |
|---|---|---|
| Nạp bản nháp | `POST /admin/halls/{hall_id}/seat-layouts/import` | Gửi SVG trong trường multipart `file`. Response `201` trả ID bản `DRAFT`. |
| Xem các phiên bản | `GET /admin/halls/{hall_id}/seat-layouts?limit=20` | Trả `data` và `next_cursor`, phiên bản mới nhất trước. Trang tiếp theo gửi lại cursor cho cùng hall. Danh sách có cả `DRAFT` và `PUBLISHED`. |
| Duyệt bản nháp | `GET /admin/seat-layouts/{layout_id}` và `GET /admin/seat-layouts/{layout_id}/svg` | Trả tổng quan khu và SVG của cùng phiên bản. Dùng `GET /admin/seat-layouts/{layout_id}/zones/{zone_id}` để đọc chi tiết một khu. |
| Công bố | `POST /admin/seat-layouts/{layout_id}/publish` | Response `200` trả phiên bản `PUBLISHED` và `published_at`. Gọi lại trả cùng ID và giữ thời điểm công bố lần đầu. |
| Tạo suất | `POST /admin/events/{event_id}/showtimes` | Response `201` trả suất và phiên bản sơ đồ đã gắn. Backend khởi tạo tồn kho cho suất từ chính phiên bản này. |
| Sửa suất | `PATCH /admin/showtimes/{showtime_id}` | Response `200` trả thông tin đã cập nhật. Có thể đổi phiên bản trước `sale_opens_at`. Nếu đổi phiên bản, backend dựng lại tồn kho của suất trong cùng transaction. |

Ví dụ body tạo suất. Hai UUID là giá trị minh họa. Thay chúng bằng ID hall và ID phiên bản đã công bố thuộc hall đó.

```json
{
  "hall_id": "8dd1178e-b167-43e1-aef9-7cf6d0be694d",
  "seat_layout_id": "6b702162-7f82-4cf7-93c1-cf65ce8b313a",
  "starts_at": "2027-02-20T19:00:00+07:00",
  "ends_at": "2027-02-20T21:00:00+07:00",
  "sale_opens_at": "2027-02-01T09:00:00+07:00",
  "sale_closes_at": "2027-02-20T18:00:00+07:00"
}
```

## Quy tắc khi công bố và gắn sơ đồ

- **Công bố một phiên bản cụ thể.** Backend khóa dòng layout trước khi cập nhật. `COALESCE(published_at, :now)` giữ thời điểm công bố lần đầu kể cả khi gọi lại. Phiên bản mới được nạp qua luồng import và có ID riêng.
- **Chọn sơ đồ cùng hall và đã công bố.** Khi tạo hoặc sửa suất, backend kiểm tra hall thuộc sân đang hoạt động. Sơ đồ phải tồn tại, thuộc đúng hall và ở trạng thái `PUBLISHED`.
- **Tồn kho thuộc từng suất.** Khu `SEATED` sinh các dòng ghế `AVAILABLE` trong `showtime_seat`. Khu `STANDING` sinh một dòng `showtime_zone_stock` cho mỗi khu, lấy sức chứa từ layout và khởi tạo `sold = 0`, `held = 0`.
- **Thay sơ đồ trước khi mở bán.** Khi PATCH, backend khóa suất và kiểm tra mốc mở bán hiện tại. Chỉ khi ID sơ đồ thay đổi thì tồn kho mới được dựng lại. Tại đúng `sale_opens_at`, thao tác sửa bị khóa. Suất `CANCELLED` cũng bị khóa.

| HTTP | Mã | Khi nào gặp |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Cursor hoặc tham số phân trang sai. Với suất, các trường cần thiết bị thiếu hoặc mốc thời gian sai. |
| `404` | `NOT_FOUND` | Không tìm thấy tài nguyên của thao tác, hoặc hall thuộc sân đã ngừng sử dụng khi liệt kê, công bố hay gắn sơ đồ. |
| `409` | `SHOWTIME_LOCKED` | Sửa suất đã tới giờ mở bán hoặc đã hủy. |
| `422` | `INVALID_SEAT_LAYOUT` | Sơ đồ còn `DRAFT` hoặc thuộc hall khác. |

## Kiểm tra giữ phiên bản giữa hai suất

1. Nạp và công bố v1, rồi tạo suất A với ID của v1.
2. Nạp và công bố v2 cho cùng hall. Tạo suất B với ID của v2.
3. Đọc lại cả hai phiên bản và kiểm tra `showtime.seat_layout_id`. Suất A giữ ID v1, suất B dùng ID v2, và cả hai sơ đồ vẫn đọc được.
4. Thử sửa sơ đồ của suất tại thời điểm mở bán. API phải trả `409 SHOWTIME_LOCKED`.

Integration test trong [AdminSeatLayoutImportIntegrationTest.java](../src/test/java/vingo/catalog/web/AdminSeatLayoutImportIntegrationTest.java) kiểm tra import, CSV, xem trước, công bố lặp, phân trang và gắn phiên bản vào suất. Từ thư mục gốc repo, khởi động Docker Desktop rồi chạy:

```powershell
.\mvnw.cmd "-Dtest=AdminSeatLayoutImportIntegrationTest" test
.\mvnw.cmd verify
```

Test dùng [SVG Thiên Trường](../sources/LAYOUT__THIEN-TRUONG.svg) làm fixture. Giữ file này trong checkout chạy test. Giao diện zoom và mini map dùng các API xem trước khi team tích hợp frontend.

## Upload và cache

Mọi profile kế thừa giới hạn upload từ `application.yml`: mỗi file tối đa 10 MiB, tổng multipart request tối đa 11 MiB. File vượt giới hạn trả HTTP 413 với `code=VALIDATION_ERROR` qua bộ xử lý lỗi Spring MVC hiện có. Caddy trong `deploy/Caddyfile` hiện chuyển tiếp request tới API và chưa đặt giới hạn body riêng. Khi bổ sung giới hạn tại proxy, cần dành đủ 11 MiB cho request multipart để giữ giới hạn file 10 MiB tại backend.

Sau khi đổi phiên bản sơ đồ và thay inventory trong cùng transaction, service phát `CatalogChanged` với sự kiện và suất bị ảnh hưởng. Search cập nhật thế hệ cache sau commit. Transaction rollback giữ nguyên cache và inventory. Trạng thái suất trả về admin được tính bằng `ShowtimeStatus.of` và `Clock`, cùng quy tắc với booking và search.

## Đánh giá migration trước triển khai

Theo xác nhận hiện tại, `V20261111_1100` chỉ được áp dụng ở local/test. Migration này phù hợp với bước dựng schema ban đầu; thêm identity và đổi `seat.x/y` từ integer sang numeric có thể viết lại dữ liệu và giữ khóa trên bảng đã lớn. Khi chạy lên môi trường có dữ liệu, cần đo thời gian trên bản sao dữ liệu và xác định cửa sổ triển khai.

Nếu triển khai online trên dữ liệu lớn, cần một đợt thay đổi riêng có thứ tự tương thích ứng dụng:

1. Thêm version nullable với sequence/default cho bản ghi mới, và cột tọa độ numeric nullable mới. Ứng dụng trong giai đoạn chuyển tiếp đọc giá trị mới với fallback sang cột cũ và ghi cả hai biểu diễn khi tương thích.
2. Backfill theo lô có commit giữa các lô, kiểm tra tiến độ và khả năng chạy lại. Tránh gom toàn bộ backfill vào một transaction Flyway dài.
3. Tạo các unique index bằng `CREATE UNIQUE INDEX CONCURRENTLY` trong migration không chạy transaction, rồi gắn constraint. Thêm CHECK dưới dạng `NOT VALID` và validate ở bước riêng.
4. Chuyển toàn bộ nơi đọc/ghi sang cột mới sau khi kiểm tra dữ liệu và triển khai phiên bản ứng dụng tương thích.
5. Xóa cột cũ và fallback trong migration tiếp theo sau khi các instance cũ đã ngừng chạy.

Các bước này cần chốt cùng quy trình deploy và rollback. Bản sửa review hiện tại giữ migration ban đầu và ghi nhận kế hoạch chuyển đổi trên.
