# CP7 · Nghiệm thu backend quản lý sân

CP7 kiểm tra chuỗi API từ tạo sân đến gắn sơ đồ đã công bố vào suất diễn. Phạm vi hiện tại là backend, tiếp nối [CP6](venue-management-cp6.md). Giao diện zoom, mini map và kiểm tra desktop/điện thoại thuộc đợt tích hợp frontend đã thống nhất riêng.

## Cách chạy

Yêu cầu Java 25 và Docker Desktop đang chạy Linux containers. Bộ test kế thừa `IntegrationTest`, dùng PostgreSQL 18 và Redis 7 thật qua Testcontainers; Flyway dựng schema trên database test. Không cần cấu hình database staging hoặc token thật.

```powershell
.\mvnw.cmd "-Dtest=AdminVenueAcceptanceTest" test
.\mvnw.cmd verify
git diff --check
```

`AdminVenueAcceptanceTest` gọi server trên cổng ngẫu nhiên bằng `java.net.http.HttpClient`, đi qua HTTP, multipart, xác thực JWT và controller thật. Test tự tạo tài khoản và token; token không được ghi vào tài liệu hoặc file mẫu.

## Kịch bản nghiệm thu xuyên suốt

| Bước | Thao tác và kết quả phải đạt |
|---|---|
| 1 | Tạo sân qua API, tạo hall `Sân chính`, sửa thông tin và tìm lại đúng sân bằng bộ lọc tên. |
| 2 | Import `sources/LAYOUT__THIEN-TRUONG.svg` thành v1 `DRAFT`: 27 khu, gồm 22 khu có ghế và 5 khu đứng, tổng 6.699 ghế; `view_box` là 1600 × 1200. |
| 3 | Đọc tổng quan, SVG và chi tiết hai khu đại diện: `B3-T1` có ghế và hướng sân, `B11-T2` có sức chứa 1.000 và không có danh sách hàng ghế. |
| 4 | Tải hai CSV, đối chiếu toàn bộ 27 dòng khu và 6.699 dòng ghế với database; kiểm tra kiểu nội dung và header tải tệp. |
| 5 | Tạo một sự kiện nền `DRAFT` trực tiếp trong database test. Thử gắn sơ đồ nháp: nhận `422 INVALID_SEAT_LAYOUT`, không sinh suất. Công bố v1 và tạo suất A thành công. |
| 6 | Import v2 từ bản sao SVG, chỉ đổi sức chứa `B11-T2` thành 1.001. Công bố v2 và tạo suất B. A vẫn trỏ v1, B trỏ v2; mỗi suất có đúng tập 6.699 ghế `AVAILABLE` và 5 dòng tồn kho khu đứng, `sold = held = 0`. Sức chứa khu kiểm tra lần lượt là 1.000 và 1.001. |
| 7 | Đọc lại SVG và hai CSV của v1: nội dung giữ nguyên sau khi công bố v2. Đọc zone của v2 qua URL v1 phải trả `404 NOT_FOUND`. |
| 8 | Đưa Clock đến đúng giờ mở bán. PATCH đổi sơ đồ suất A trả `409 SHOWTIME_LOCKED`; tồn kho và phiên bản của A giữ nguyên. Công bố lại v1 giữ thời điểm công bố ban đầu. |
| 9 | Ngừng sử dụng sân khi còn suất chưa kết thúc: `409 VENUE_IN_USE`. Đến đúng thời điểm kết thúc các suất: `204`; sân biến mất khỏi danh sách, tạo hall mới trả `404 NOT_FOUND`. |

Mã thành phố trong fixture là `HN` để tuân theo danh mục hiện tại của contract. SVG Thiên Trường được dùng làm dữ liệu hình học kiểm thử, không phải hồ sơ địa lý sân thật.

Sự kiện nền được seed vì API tạo/quản lý sự kiện thuộc bước triển khai tiếp theo. Vì vậy CP7 này xác nhận quản lý sân và gắn sơ đồ vào suất; chưa xác nhận luồng tạo sự kiện qua API hoặc bán vé.

## Phân quyền và định dạng lỗi

Hai bộ test tham số hóa chạy trên 10 thao tác: import, danh sách phiên bản, tổng quan, SVG, chi tiết khu, hai CSV, publish, tạo suất và sửa suất. Mỗi thao tác phải trả:

- Không có token: `401`, `code = UNAUTHORIZED`.
- Token `BUYER`: `403`, `code = FORBIDDEN`.

Các phản hồi lỗi được kiểm tra `application/problem+json`. Mọi request của bài nghiệm thu phải nhận lại đúng header `X-Request-Id` đã gửi. Phân quyền thao tác hồ sơ sân và hall được kiểm tra thêm trong `AdminVenueIntegrationTest`.

Các bộ test CP3–CP6 tiếp tục kiểm tra trường bắt buộc, tên hall trùng, upload trống/sai kiểu/quá dung lượng/SVG sai, cursor, sai hall, sơ đồ nháp, thay tồn kho trước mở bán, trạng thái suất tính khi đọc, xóa sân đồng thời với tạo suất và làm mới cache sau đổi sơ đồ.

## Bộ mẫu bàn giao frontend

Khi bài nghiệm thu xuyên suốt thành công, test tạo `target/cp7-handoff/` từ response thực tế:

| File | Mục đích |
|---|---|
| `overview-v1.json`, `overview-v2.json` | Phiên bản đã công bố, `view_box`, thông tin khu và vùng zoom. |
| `seated-zone-v1.json` | Khu `B3-T1`: khung khu, hướng sân, hàng ghế, tọa độ và ID ghế. |
| `standing-zone-v1.json` | Khu `B11-T2`: sức chứa, không có danh sách ghế. |
| `layout-v1.svg` | SVG chuẩn hóa, cùng hệ tọa độ với JSON. |
| `zones-v1.csv`, `seats-v1.csv` | Dữ liệu xuất đã đối chiếu database. |
| `scenario.json` | Các ID liên quan và body mẫu tạo suất; không có token. |

Các UUID chỉ thuộc database test tạm thời và không dùng để gọi môi trường thật. Đây là dữ liệu tham khảo cho frontend, không phải dữ liệu seed staging. File trong `target/` không commit; chạy lại test để tái tạo. Bài nghiệm thu dùng sân test đã ngừng sử dụng ở cuối luồng, nên các file mẫu không biểu thị trạng thái sân đang hoạt động.

## Bàn giao sang quản lý sự kiện

Đầu ra phần sân đã có: venue, hall, phiên bản sơ đồ đã công bố, khu, ghế và tồn kho theo suất. Phần sự kiện tiếp theo sử dụng các ID này để triển khai danh sách/chi tiết và tạo/sửa sự kiện nháp, sau đó mới cấu hình hạng vé, giá, cách cấp chỗ và công bố sự kiện.

Thiết kế chi tiết, quyết định hai poster và các điểm tích hợp với Search/Booking được ghi tại [Quản lý sự kiện](event-management.md).

PR/review của team và thử Swagger trên staging là bước nghiệm thu môi trường dùng chung theo `CLAUDE.md`. Chưa có bằng chứng chạy staging trong CP7 local. Kiểm tra UI trên desktop và điện thoại thực hiện khi có frontend nối các API này.

## Kết quả chạy

Nghiệm thu backend local ngày **12/11/2026**, trên nền commit `f648f76` cùng các thay đổi CP7 trong working tree:

| Kiểm tra | Kết quả |
|---|---|
| `AdminVenueAcceptanceTest` | 21 test qua: 1 luồng HTTP xuyên suốt và 20 ca phân quyền. |
| `mvnw verify` | `BUILD SUCCESS` lúc 13:53:32 (UTC+07), 419 test qua, 0 failure, 0 error, 0 skipped; JAR được đóng gói thành công. |
| `ModularityTest` | 2 test qua trong lượt `verify` trên. |
| Bộ mẫu bàn giao | 8 file trong `target/cp7-handoff/`; JSON đọc được, CSV có đúng 27 dòng khu và 6.699 dòng ghế, không tính header. |

Log chạy riêng: `target/cp7-acceptance.log`. Log toàn repo: `target/cp7-verify.log`. Báo cáo test: `target/surefire-reports/vingo.catalog.web.AdminVenueAcceptanceTest.txt` và `target/surefire-reports/vingo.ModularityTest.txt`.

**Kết luận: CP7 backend local đạt nghiệm thu, có thể chuyển sang thiết kế chi tiết quản lý sự kiện.** PR/review, Swagger staging và UI chưa được đánh dấu hoàn thành trong biên bản này.
