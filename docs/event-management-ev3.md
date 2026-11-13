# EV3 · Chọn sân, hall, sơ đồ và tạo suất theo sự kiện

Nhánh triển khai: `feat/create_events-luannt`. Tiếp nối [EV2 — hồ sơ sự kiện](event-management-ev2.md). EV3 nghiệm thu backend từ sự kiện nháp đến các suất với địa điểm và phiên bản sơ đồ riêng; hạng giá, khóa/mở ghế và công bố sự kiện thuộc EV4–EV6.

## Luồng API

Mọi API dưới đây có tiền tố `/api/v1` và yêu cầu JWT của `ADMIN`.

1. `POST /admin/events` tạo event `DRAFT`, hoặc mở lại bằng `GET /admin/events/{event_id}`.
2. `GET /admin/venues` chọn sân. Mỗi phần tử có `halls`; sân chưa có hall trả `[]`. Hall được lấy theo cả trang bằng một query, không chạy query cho từng sân. Bộ lọc và cursor của danh sách sân giữ nguyên.
3. `GET /admin/halls/{hall_id}/seat-layouts` chọn phiên bản theo `id`, `name`, `version`, `status`. Chỉ bản `PUBLISHED` được gắn vào suất; không tự chọn phiên bản mới nhất.
4. Xem trước bằng `GET /admin/seat-layouts/{layout_id}`, `/svg`, `/zones/{zone_id}`. Tên sơ đồ có trong metadata tổng quan, không thay đổi hệ tọa độ/SVG/ghế.
5. `POST /admin/events/{event_id}/showtimes` gửi hall, phiên bản và các mốc giờ; backend tạo suất cùng tồn kho của phiên bản trong một transaction.
6. `GET /admin/events/{event_id}` trả mọi suất, kể cả nháp, với địa điểm của từng suất. Một sự kiện có thể dùng nhiều sân/hall/phiên bản.
7. `PATCH /admin/showtimes/{showtime_id}` chỉnh trước giờ mở bán hiện tại. Nếu cần bố trí khác, quay sang Quản lý sân để import/công bố phiên bản rồi chọn lại cho suất.

Không sửa khu hoặc vị trí ghế vật lý trong sự kiện. Công bố layout mới không tự thay `seat_layout_id` của suất đã tạo.

## Tên cấu hình sơ đồ

Import SVG thay đổi request multipart: **bắt buộc `file` và `name`**. Client upload cũ cần bổ sung trường tên.

```text
POST /api/v1/admin/halls/{hall_id}/seat-layouts/import
Content-Type: multipart/form-data

name = Concert – sân khấu đầu B
file = layout.svg
```

- Tên không trắng, tối đa 200 ký tự, bỏ khoảng trắng hai đầu khi lưu.
- Được phép dùng cùng tên cho nhiều phiên bản; giao diện nên hiện tên cùng số phiên bản/trạng thái.
- Tên có trong response import, danh sách, tổng quan và publish.
- `PATCH /admin/seat-layouts/{layout_id}` với `{ "name": "Bóng đá" }` đổi tên bản `DRAFT`. Bản đã công bố trả `409 SEAT_LAYOUT_LOCKED`.
- Đổi tên không đổi ID, số phiên bản, khu, ghế, SVG hoặc liên kết của suất.
- Sân đã ngừng sử dụng không được import, đổi tên hoặc công bố layout; trả `404 NOT_FOUND`.

Migration mới: `V20261112_1700__catalog_seat_layout_name.sql`. Layout cũ được backfill `Sơ đồ v<version>`, giữ nguyên ID và liên kết; constraint bắt tên không trắng và tối đa 200 ký tự. Default `Sơ đồ` chỉ để tương thích seed/fixture nội bộ cũ; API import vẫn bắt admin nhập tên. Không đặt UNIQUE cho tên.

## Tạo và sửa suất

Ví dụ body tạo, thay các UUID bằng ID thật:

```json
{
  "hall_id": "8dd1178e-b167-43e1-aef9-7cf6d0be694d",
  "seat_layout_id": "6b702162-7f82-4cf7-93c1-cf65ce8b313a",
  "sale_opens_at": "2027-02-01T02:00:00Z",
  "sale_closes_at": "2027-02-20T11:00:00Z",
  "starts_at": "2027-02-20T12:00:00Z",
  "ends_at": "2027-02-20T14:00:00Z"
}
```

Event `DRAFT` tạo suất `DRAFT`; event `PUBLISHED` tạo suất lưu `SCHEDULED`. Trạng thái `ON_SALE`, `SALES_CLOSED`, `FINISHED` được tính khi đọc từ Clock và các mốc giờ.

| HTTP/code | Điều kiện |
|---|---|
| `400 VALIDATION_ERROR` | Thiếu thông tin tạo suất, kết thúc không sau bắt đầu, đóng bán không sau mở bán hoặc sau lúc kết thúc. Tên layout thiếu/trắng/quá dài. |
| `401 UNAUTHORIZED` / `403 FORBIDDEN` | Thiếu token hoặc vai trò BUYER. |
| `404 NOT_FOUND` | Event/suất/hall/layout không tồn tại, hoặc hall thuộc sân không còn hoạt động. |
| `409 EVENT_LOCKED` | Tạo/sửa suất của event `ENDED` hoặc `CANCELLED`. |
| `409 SHOWTIME_LOCKED` | Suất đã hủy hoặc đã tới `sale_opens_at` hiện tại; hoặc đổi layout khi đã có đơn hay tồn kho HELD/SOLD. |
| `422 INVALID_SEAT_LAYOUT` | Layout chưa công bố hoặc không thuộc hall đã chọn. |

PATCH giữ nguyên trường không gửi/null. Khóa theo mốc mở bán **đang lưu**, nên không thể sửa mốc đó ra tương lai để mở khóa suất đã bán. Nếu chỉ sửa giờ, tồn kho giữ nguyên. Nếu đổi layout trước mở bán, backend khóa tồn kho cũ, kiểm tra chưa có đơn/chỗ đã cấp rồi dựng lại tồn kho đúng phiên bản trong transaction. Có lịch sử đơn, kể cả EXPIRED, cũng không đổi layout để giữ tham chiếu lịch sử.

Giữ thứ tự khóa `event → showtime`, sau đó venue/tồn kho khi cần. Tạo/sửa suất của cùng event được đồng bộ với PATCH hồ sơ event. Không gọi HTTP ngoài hoặc dùng Redis làm tồn kho.

## Cache, log và phối hợp team

- Tạo suất và mọi thay đổi dữ liệu suất phát `CatalogChanged(eventId, {showtimeId})`, kể cả sửa giờ mà không đổi layout.
- Publish layout phát `CatalogChanged` cho các event/suất đang tham chiếu; không bịa event ID khi layout chưa gắn suất.
- Sửa/ngừng sử dụng sân phát `VenueChanged(venueId)` để Search đọc lại địa điểm.
- Listener xử lý `AFTER_COMMIT`; rollback không tăng thế hệ cache. PATCH suất không làm thay đổi dữ liệu không phát event.
- Log ghi ID nghiệp vụ (`event_id`, `showtime_id`, `seat_layout_id`) cùng `request_id` của request; không log nội dung SVG hoặc body.

Luồng khởi tạo/thay inventory của CP6 được giữ trong Catalog theo checkout hiện tại. EV3 không thêm dependency Catalog → Booking và không tự sửa module của Danh; việc thống nhất facade inventory cho bước công bố event vẫn cần phối hợp trước EV6.

## Kiểm tra

```powershell
.\mvnw.cmd "-Dtest=AdminEventShowtimeIntegrationTest,SeatLayoutNameMigrationTest,AdminVenueIntegrationTest,AdminSeatLayoutImportIntegrationTest,AdminVenueAcceptanceTest" test
.\mvnw.cmd clean verify
```

Bộ focused chạy trên PostgreSQL 18/Redis 7 đã qua **93 ca**, không lỗi hoặc bỏ qua. Trong đó:

- 16 ca mới trong `AdminEventShowtimeIntegrationTest`: hành trình API nhiều sân bằng SVG Thiên Trường thật, tên/rename/giới hạn tên, layout cũ, event bị khóa, tồn kho, cache commit/rollback.
- 1 ca `SeatLayoutNameMigrationTest`: Flyway dựng schema test ở phiên bản trước, seed layout đã công bố và suất cũ, nâng migration rồi kiểm tra backfill, giữ liên kết và constraint.
- Test migration dùng connection vật lý độc lập, không thay `search_path` của pool ứng dụng; sau cleanup kiểm tra pool vẫn đọc schema `public`.
- Các bộ CP3–CP7 tiếp tục kiểm tra 4xx, phân quyền, CSV, xem trước, phiên bản, tồn kho và tranh chấp xóa sân/tạo suất. Fixture upload được bổ sung trường `name`, không bỏ test cũ.

## Kết quả nghiệm thu local ngày 12/11/2026

`mvnw clean verify` hoàn tất lúc **16:55:43 (UTC+07)**: **BUILD SUCCESS**, JAR đóng gói thành công.

| Kiểm tra | Kết quả |
|---|---|
| Toàn repo | **510 test**, 0 failure, 0 error, 0 skipped. |
| Luồng EV3 nhiều sân, tên layout, trạng thái và cache | 16 test qua. |
| Nâng migration layout cũ | 1 test qua. |
| API sân/hall | 34 test qua, gồm danh sách hall và phân trang sân. |
| Import/xem trước/phiên bản/tồn kho CP4–CP6 | 19 test qua. |
| Nghiệm thu CP7 và phân quyền layout | 23 test qua. |
| `ModularityTest` | 2 test qua trong lượt toàn repo. |
| `git diff --check` | Không có lỗi khoảng trắng. |

Lượt toàn repo đầu tiên phát hiện test migration đổi schema trên pool dùng chung, làm ảnh hưởng các test chạy sau. Test đã được sửa dùng datasource không pool với connection vật lý độc lập; lượt `clean verify` sau sửa qua toàn bộ. Không skip/xóa test hoặc thay source module khác để né lỗi.

Log cuối: `target/ev3-verify.log`, bản sao `.local-handoff/ev3-verify.log`. Báo cáo chi tiết nằm trong `target/surefire-reports/`.

**Kết luận: EV3.1–EV3.5 đạt nghiệm thu backend local, có thể chuyển sang EV4 — hạng giá và gán giá khu.** Thay đổi EV3 chưa tự commit/push. Staging, UI zoom/minimap và review PR là các bước nghiệm thu riêng; không đánh dấu đã hoàn thành từ test local.
