# CP2 · Contract và schema quản lý sân

Đề xuất để duyệt trong PR OpenAPI. File mẫu `sources/LAYOUT__THIEN-TRUONG.svg` có một hệ tọa độ `1600 × 1200`, 22 khu có ghế, 6.699 ghế định danh và 5 khu bán theo sức chứa.

## Contract đề xuất

Các đường dẫn bên dưới có tiền tố `/api/v1` và yêu cầu vai trò `ADMIN`.

| API | Yêu cầu và kết quả |
|---|---|
| `GET /admin/venues` | Giữ bộ lọc `q`, `city` và phân trang cursor. |
| `POST /admin/venues` | Bắt buộc `name`, `type`, `address`, `city`. Trả `201`. |
| `PATCH /admin/venues/{venue_id}` | Chỉ cập nhật trường được gửi. Trả `200`. |
| `DELETE /admin/venues/{venue_id}` | Ngừng sử dụng sân. Trả `204` hoặc `409 VENUE_IN_USE` nếu còn suất chưa kết thúc. |
| `POST /admin/venues/{venue_id}/halls` | Tạo hall. Sân bóng thông thường dùng một hall tên Sân chính. |
| `GET /admin/halls/{hall_id}/seat-layouts` | Liệt kê phiên bản theo cursor, mới nhất trước. |
| `POST /admin/halls/{hall_id}/seat-layouts` | Giữ `application/json` hiện có. Bổ sung `multipart/form-data` với trường `file` bắt buộc chứa SVG. Import hợp lệ trả `201` với phiên bản `DRAFT` và số khu, ghế. |
| `GET /admin/seat-layouts/{layout_id}` | Trả metadata phiên bản, `view_box` và danh sách khu gồm mã, loại, sức chứa, khung zoom và SVG ID tương ứng. |
| `GET /admin/seat-layouts/{layout_id}/svg` | Trả SVG đã chuẩn hóa với kiểu `image/svg+xml`. |
| `GET /admin/seat-layouts/{layout_id}/zones/{zone_id}` | Trả thông tin khu. Với `SEATED` có hàng và ghế. Với `STANDING` có sức chứa. |
| `GET /admin/seat-layouts/{layout_id}/zones.csv` | Tải CSV UTF-8 từ dữ liệu đã lưu. |
| `GET /admin/seat-layouts/{layout_id}/seats.csv` | Tải CSV UTF-8 từ dữ liệu đã lưu. |
| `POST /admin/seat-layouts/{layout_id}/publish` | Công bố phiên bản và khóa dữ liệu sơ đồ. Gọi lại cho cùng phiên bản trả trạng thái hiện tại. |

Upload thiếu file hoặc sai multipart trả `400`. Kiểu file không được hỗ trợ trả `415`. SVG đúng kiểu nhưng vi phạm quy ước trả `422` với ProblemDetail và danh sách lỗi gồm `layer_id`, `reason` và thông báo. Tài nguyên không tồn tại hoặc zone không thuộc layout trong URL trả `404`. Chốt mã lỗi nghiệp vụ trong PR cùng ví dụ phản hồi.

Hai điểm cần đưa vào PR là tách schema tạo và sửa sân, và bổ sung nguồn mã tỉnh/thành cho sân Thiên Trường. `CityCode` hiện chỉ khai báo `HCM`, `HN` và `DN`.

## Schema đề xuất

| Bảng | Dữ liệu cần lưu |
|---|---|
| `venue` | Giữ tên cột `city` theo contract hiện tại. Dùng `name`, `type`, `address`, `city` cho hồ sơ bắt buộc khi tạo. `district`, `lat`, `lng` và `info` là tùy chọn. Kiểm tra tọa độ địa lý theo cặp. |
| `hall` | Giữ `venue_id`, `name` và khóa duy nhất theo sân. |
| `seat_layout` | Bổ sung `version` duy nhất theo hall và `schema_version` cho quy ước SVG. Giữ `DRAFT`, `PUBLISHED` và thời điểm công bố. |
| `seat_layout_svg` | Đề xuất bảng 1–1 theo `seat_layout_id` để lưu nội dung SVG đã chuẩn hóa và `view_box`. Layout tạo qua JSON có thể không có dòng SVG. |
| `zone` | Bổ sung `code` duy nhất theo layout, `stand_code`, `level`, ID đường viền, ID nhóm nội dung, ID cạnh hướng sân và khung zoom. Dùng `name` làm nhãn hiển thị. Giữ `kind` và `capacity`. |
| `seat` | Giữ khóa duy nhất `(zone_id, row_label, number)` và `seat_type_id`. Mở rộng `x`, `y` sang `numeric` để giữ phần lẻ từ Figma. Mặc định ghế import là `STANDARD`. |
| `showtime` | Dùng `seat_layout_id` hiện có. Khi gắn sơ đồ cho suất, phiên bản phải đã công bố và thuộc đúng hall. Suất đã liên kết giữ nguyên ID phiên bản. |

SVG v1 dùng chung hệ tọa độ toàn sân. Chuẩn hóa phép biến đổi lồng nhau khi tính tâm ghế và khung zoom. SVG chỉ render các phần tử và tham chiếu nội bộ được phép. Một file tạo một bản nháp trong một giao dịch. `SEATED` tính sức chứa từ ghế đã đọc, còn `STANDING` lấy số nguyên dương từ ID khu.

Các mã khu hiện tại có dạng như `A2L-T1` và `C-T2`. Với SVG v1, đề xuất lấy khán đài từ chữ đầu A–D và tầng từ hậu tố `-T<n>` khi có. Nhãn mặc định là mã khu. Nếu sân cần mã khán đài ngoài quy ước đó, cần thống nhất cách khai báo trước khi mở rộng format.

- `zones.csv` có các cột `zone_code,label,stand_code,level,kind,capacity,svg_id`.
- `seats.csv` có các cột `zone_code,row_label,seat_number,x,y`.
- Mini map dùng cùng SVG và vùng đang zoom. Giá, SKU, số chỗ mở bán và cách cấp chỗ thuộc cấu hình suất.

## Tiêu chí duyệt CP2

- [ ] Duyệt contract upload, read, CSV và publish cùng mô hình lưu trữ.
- [ ] Chốt danh mục mã tỉnh/thành và quy ước khán đài ngoài A–D nếu nằm trong phạm vi bản đầu.
- [ ] Chuẩn bị PR contract theo quy tắc repo. Sau khi contract được duyệt, sinh interface trước khi viết controller.
