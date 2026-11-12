# EV2 · Hồ sơ sự kiện cho admin

Nhánh: `feat/create_events-luannt`. Phạm vi EV2 là tạo, đọc chi tiết, liệt kê và chỉnh sửa thông tin chung của sự kiện. Kế hoạch tổng thể ở [event-management.md](event-management.md).

Luân đã xác nhận Đức chốt hướng contract trong chat. Các thay đổi local vẫn phải đưa vào PR của team; tài liệu này không xác nhận PR đã merge hoặc đã chạy staging.

## API đã triển khai

Tất cả đường dẫn có tiền tố `/api/v1`, yêu cầu Bearer JWT của `ADMIN`. Không có token trả `401 UNAUTHORIZED`; token `BUYER` trả `403 FORBIDDEN`.

| API | Hành vi |
|---|---|
| `POST /admin/events` | Tạo `DRAFT`; mặc định 10 vé mỗi đơn nếu không cung cấp; chưa tạo suất, giá hoặc tồn kho. |
| `GET /admin/events/{event_id}` | Đọc mọi trạng thái, gồm nháp; trả hai poster, attributes, hạng giá và mọi suất, kể cả suất nháp/hủy, với địa điểm riêng từng suất. Không tồn tại trả `404 NOT_FOUND`. |
| `GET /admin/events` | Lọc `q`, `status`, `category`; phân trang bằng cursor, limit mặc định 20, từ 1 đến 100; có cả event nháp. |
| `PATCH /admin/events/{event_id}` | Sửa thông tin chung của event `DRAFT` hoặc `PUBLISHED`; không đổi trạng thái, giá, sơ đồ hay tồn kho. Event `ENDED`/`CANCELLED` trả `409 EVENT_LOCKED`. |

## Tạo và chỉnh sửa

Tạo nháp yêu cầu `title` không trắng và `category` là `FOOTBALL`, `CONCERT`, `THEATER` hoặc `OTHER`. Tiêu đề được bỏ khoảng trắng hai đầu. `age_limit` là metadata không âm, không triển khai xác minh tuổi người mua trong EV2. Giới hạn vé từ 1 đến 20. Hai poster là URI tuyệt đối HTTP/HTTPS có host.

Ví dụ tạo nháp:

```json
{
  "title": "Trận đấu minh họa",
  "category": "FOOTBALL",
  "attributes": { "home_team": "Đội A", "away_team": "Đội B" },
  "poster_mobile_url": "https://example.com/mobile.jpg",
  "poster_desktop_url": "https://example.com/desktop.jpg",
  "max_tickets_per_order": 4
}
```

Ví dụ sửa tên:

```json
{ "title": "Tên sự kiện mới" }
```

Sau PATCH này giới hạn vé vẫn là **4**; hai poster và attributes giữ nguyên.

Quy tắc PATCH được định nghĩa riêng bằng `EventPatchInput`, không có default:

- Trường không gửi hoặc giá trị `null`: giữ nguyên, không xóa dữ liệu.
- `{}` hoặc toàn bộ trường là null: không thay đổi database, `updated_at` hoặc thế hệ cache.
- `attributes` khác null thay thế toàn bộ object, không merge sâu; `{}` làm rỗng object.
- `description: ""` cho phép lưu mô tả rỗng. `title` trắng, poster rỗng/sai, tuổi âm, giới hạn sai hoặc attributes không phải object trả `400 VALIDATION_ERROR`.
- Chưa hỗ trợ xóa poster/tuổi bằng null. Client không nên gửi cả form với default khi chỉ muốn sửa một trường.

Backend khóa dòng event bằng `SELECT ... FOR UPDATE` và chỉ ghi các cột có giá trị PATCH khác null. Hai cập nhật đồng thời vào các trường khác nhau không làm mất thay đổi của nhau. Cập nhật lỗi rollback, không cập nhật dở một phần. `created_at`, `published_at` và trạng thái được giữ nguyên.

## Danh sách và cursor

Ví dụ:

```text
GET /api/v1/admin/events?q=Concert&status=DRAFT&category=CONCERT&limit=20
```

`q` tìm chuỗi con không phân biệt hoa/thường; bỏ khoảng trắng hai đầu. `%` và `_` là ký tự thường, không phải wildcard. Đây không phải tìm kiếm full-text/unaccent của API public.

Thứ tự cố định: `created_at DESC, id DESC`. Cursor Base64 URL chứa vị trí dòng cuối được trả về; không phải token xác thực hoặc mã hóa bảo mật. Client dùng nguyên `next_cursor` cùng bộ lọc để đọc trang tiếp theo. Không dùng `updated_at` làm vị trí nên sửa hồ sơ không làm sự kiện đổi trang.

Backend lấy dư một dòng để biết còn trang. Trang cuối và trang rỗng luôn có `next_cursor: null`. Cursor sai hoặc limit/bộ lọc sai trả `400 VALIDATION_ERROR`. Các sự kiện mới nằm trước cursor không được chèn ngược vào chuỗi trang đang duyệt; tải lại trang đầu để thấy chúng.

## Dữ liệu và phối hợp Search

Migration `V20261112_1500__catalog_event_metadata.sql` bổ sung `event.poster_mobile_url`, `event.poster_desktop_url`, `event.age_limit` và constraint tuổi không âm. Poster cũ được sao chép vào cả hai trường mới để giữ dữ liệu; sau đó hai trường được ghi độc lập.

**Bàn giao cho Đức:** các API admin đã dùng hai cột mới và `age_limit`. Cột `poster_url` cũ vẫn giữ nguyên. Mapping public của Search/reader cũ còn dùng cột cũ; EV2 không tự thay code module Search hoặc tự chọn ảnh fallback cho API public. Cần phối hợp chuyển mapping trước nghiệm thu luồng công bố ở các mốc sau. Dữ liệu poster cũ không hợp lệ được bỏ khỏi response admin kèm WARN chỉ có event ID/tên field; cập nhật trường khác không ghi đè URL cũ đó.

Tạo nháp phát `CatalogChanged` với tập suất rỗng. PATCH có thay đổi phát `CatalogChanged(eventId, showtimeIds)` với các suất liên quan, trong cùng transaction. Listener Search chạy `AFTER_COMMIT`; rollback và PATCH không có giá trị không tăng thế hệ cache. Test làm nóng Search trước PATCH và kiểm tra dữ liệu mới sau commit, đồng thời giữ nguyên giá và tồn kho.

GET admin đọc trực tiếp database, không dùng read model public chỉ cho `PUBLISHED`. Trạng thái suất vẫn tính khi đọc qua `ShowtimeStatus.of` và Clock; GET không đổi trạng thái lưu trong database. Event nháp không xuất hiện qua GET chi tiết public.

## Kiểm tra và nghiệm thu

Yêu cầu Java 25, Docker Desktop Linux containers. Test dùng PostgreSQL 18 và Redis 7 thật, Flyway chạy trên database test.

```powershell
.\mvnw.cmd "-Dtest=AdminEventCreateIntegrationTest,AdminEventDetailIntegrationTest,AdminEventListIntegrationTest,AdminEventUpdateIntegrationTest" test
.\mvnw.cmd clean verify
```

Giữ `java.autobuild.enabled = false` trong `.vscode/settings.json` khi Maven build để tránh editor ghi bytecode vào `target/classes` trong lúc chạy. Nếu cần lưu log khi `clean`, ghi ra ngoài `target` trước, vì `clean` xóa thư mục này.

| Bộ test | Nội dung |
|---|---|
| `AdminEventCreateIntegrationTest` | Tạo nháp, hai poster, dữ liệu lưu, mặc định/validation, 401/403. |
| `AdminEventDetailIntegrationTest` | Mọi trạng thái, suất nháp, nhiều sân, thời gian, 401/403/404. |
| `AdminEventListIntegrationTest` | Bộ lọc kết hợp, ký tự %, _, cursor với thời gian trùng, trang lẻ/cuối/rỗng, bản ghi mới giữa các trang, 400/401/403. |
| `AdminEventUpdateIntegrationTest` | Giữ trường không gửi/null và giới hạn 4 vé, thay metadata/attributes, no-op, 400/401/403/404/409, concurrent PATCH, cache commit/rollback, giữ giá/tồn kho, và luồng tạo → đọc → sửa → danh sách → đọc lại. |

### Kết quả nghiệm thu local ngày 12/11/2026

`mvnw clean verify` hoàn tất lúc **16:00:41 (UTC+07)**: **BUILD SUCCESS**, JAR được đóng gói thành công.

| Kiểm tra | Kết quả |
|---|---|
| Toàn repo | **489 test**, 0 failure, 0 error, 0 skipped. |
| Tạo sự kiện | 16 test qua. |
| GET chi tiết | 10 test qua. |
| GET danh sách | 16 test qua. |
| PATCH và nghiệm thu luồng EV2 | 28 test qua. |
| Ranh giới module | 2 test `ModularityTest` qua trong lượt toàn repo. |
| Kiểm tra diff | `git diff --check` không báo lỗi. |

Tổng **70 ca EV2** đều qua. Một assertion thời gian JDBC ở lượt focused đầu tiên đã được sửa sang đọc `OffsetDateTime` trước lượt `clean verify` này; không skip hoặc xóa test để build xanh.

Log toàn repo: `target/ev2-verify.log` (bản sao ở `.local-handoff/ev2-verify.log`). Báo cáo riêng nằm trong `target/surefire-reports/vingo.catalog.web.AdminEvent*IntegrationTest.txt`.

**Kết luận: EV2.4, EV2.5, EV2.6 đạt nghiệm thu backend local; EV2 được chốt cho luồng hồ sơ admin.** Các file chưa được tự commit/push. PR/review và Swagger staging là các bước bàn giao riêng, chưa được coi là đã hoàn thành chỉ từ test local. Bước tiếp theo là EV3: nối đầy đủ luồng chọn địa điểm/sơ đồ và quản lý suất theo kế hoạch chung.
