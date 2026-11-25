# Thiết kế và checklist chuyển SKU xuống cấp ghế

Trạng thái: đã triển khai CP1–CP5 trên nhánh làm việc. Chưa áp dụng migration lên DB triển khai.

- Đã sửa thao tác xóa hạng vé chưa sử dụng.
- Backfill chỉ gộp SKU cũ khi toàn bộ lịch giá trùng nhau, kể cả giá đã hẹn.
- CP5 đối chiếu giá cũ theo hạng đã ánh xạ, mốc hiệu lực và số tiền. Đã kiểm thử nâng cấp trên PostgreSQL với hai SKU cũ có cùng lịch giá và xác nhận cả giá hiện tại lẫn giá đã hẹn sau khi dọn bảng.
- Hai script seed đã chuyển sang schema CP5. Lượt chạy lại 14 test migration, seed và QR đều pass.
- Khóa ngoại dòng đơn kiểm tra SKU khớp với sự kiện và ghế hoặc khu đứng đã chọn.
- Đã thêm migration CP5 dọn bảng `sku_price`, bỏ `event_zone.sku_id`, bỏ `order_seat.sku_price_id` / `order_standing.sku_price_id`, và siết bảng `sku` chỉ chứa SKU ghế ngồi hoặc khu đứng.
- Admin, Search, Booking và luồng đọc đơn/vé đã chuyển sang đọc mô hình mới (`ticket_type`, `ticket_type_price`, `event_zone.ticket_type_id`, `sku`). Checklist ứng dụng bên dưới theo dõi việc hoàn thiện contract.

Xem [migration CP1–CP4](../src/main/resources/db/migration/V20261125_1200__catalog_ticket_type_and_seat_sku.sql), [migration CP5](../src/main/resources/db/migration/V20261125_1300__catalog_cleanup_sku_price_and_event_zone_sku.sql) và [kiểm thử migration](../src/test/java/vingo/catalog/TicketSkuRedesignMigrationTest.java).

## Mô hình hiện tại và mục tiêu

Schema hiện tại dùng `event_zone.sku_id` để gán một SKU cho cả khu và lưu giá theo SKU trong `sku_price`. Mô hình mới định nghĩa SKU vé ngồi là tổ hợp **sự kiện + ghế cụ thể + hạng vé**. Hạng vé sở hữu giá. Kho sở hữu số lượng và trạng thái theo suất.

Ví dụ, khu A có 10 ghế VIP và khu B có 15 ghế Standard. Cấu hình này tạo **2 hạng vé, 25 SKU**, mỗi hạng có một giá hiệu lực. Nếu hai suất dùng cùng cấu hình, kho có **50 dòng tồn ghế**, mỗi suất 25 dòng.

## Mô hình đích

| Thành phần | Trách nhiệm |
| --- | --- |
| `ticket_type` | Hạng vé trong một sự kiện. Lưu tên và metadata hiển thị của hạng. |
| `ticket_type_price` | Lịch sử giá của hạng vé. `effective_from` ghi mốc bắt đầu áp dụng từng giá. Khi admin tăng giá giữa đợt bán, giá mới có hiệu lực ngay từ lúc lưu. Khóa duy nhất là `(ticket_type_id, effective_from)`. |
| `sku` | Gắn `event_id`, `ticket_type_id` và ghế đối với vé ngồi hoặc khu đối với vé đứng. Khóa duy nhất theo `(event_id, seat_id, ticket_type_id)` cho vé ngồi và `(event_id, zone_id, ticket_type_id)` cho vé đứng. Dùng khóa ngoại ghép để kiểm tra hạng thuộc cùng sự kiện và chỗ thuộc đúng sơ đồ. |
| `event_zone` | Lưu cấu hình mở bán và tên hiển thị của khu. Mỗi SKU thuộc một khu đã cấu hình cho sự kiện. Với vé ngồi, khu được xác định từ ghế. Bỏ liên kết `sku_id` sau khi chuyển hết luồng đọc và bán. |
| `seat_inventory` | Một dòng cho mỗi `(showtime_id, seat_id)`. Ghế có sức chứa 1 trong mỗi suất. Mọi SKU của ghế dùng chung dòng tồn đó. |
| `standing_inventory` | Một dòng cho mỗi `(showtime_id, zone_id)`. Quản lý `capacity`, `held` và `sold`. Số chỗ còn lại bằng `capacity - held - sold`. |
| `order_seat`, `order_standing` | Đơn mới lưu SKU đã chọn, phiên bản giá của hạng và `unit_price` lúc giữ chỗ. Ràng buộc phiên bản giá phải thuộc đúng hạng của SKU. Đơn cũ tiếp tục đọc được giá đã chốt và thông tin vé. |

Giả định triển khai là SKU dùng chung giữa các suất của sự kiện và mỗi ghế có một hạng trong đợt đầu. Admin có thể gán hạng cho cả khu để sinh SKU hàng loạt. Nếu cần đổi hạng của cùng một ghế giữa các suất, bổ sung phạm vi `showtime_id` vào cấu hình bán trước khi triển khai nghiệp vụ đó.

## Checklist triển khai

### Schema và chuyển dữ liệu

- [x] **CP1.** Thêm migration Flyway tạo `ticket_type`, `ticket_type_price` và mở rộng SKU. Thêm khóa ngoại, kiểm tra ghế hoặc khu đứng, index tra giá theo hạng và mốc hiệu lực.
- [x] **CP2.** Xác định hạng của dữ liệu cũ từ cấu hình đã được xác nhận. Với hai SKU cũ dùng chung tên hạng dự kiến nhưng có lịch sử giá khác nhau, chốt biểu giá trước khi gộp.
- [x] **CP3.** Sinh một SKU cho mỗi cặp ghế và hạng đã cấu hình. Sinh SKU theo khu và hạng cho vé đứng. Đối chiếu số SKU, số dòng tồn và giá có hiệu lực trước và sau chuyển dữ liệu.
- [x] **CP4.** Chuyển sang giá theo `ticket_type_price`. Giữ đường đọc đơn cũ cho tới khi mọi tham chiếu tới `sku_price_id` đã được chuyển và đối chiếu. Giữ nguyên `unit_price` đã chốt.
- [x] **CP5.** Khi các luồng đã đọc mô hình mới và dữ liệu lịch sử đã được xác minh, dùng migration dọn `sku_price` và liên kết `event_zone.sku_id`.

### Contract và ứng dụng

OpenAPI dùng `ticket_type_id` làm trường chính cho ID hạng vé. API giữ các tên trường cũ để tương thích và từ chối cấu hình khu nếu hai trường ID hạng cùng được gửi với giá trị khác nhau. Kết quả tìm kiếm chỉ dùng giá của các khu có SKU tương ứng.

- [x] Cập nhật OpenAPI trước khi sửa endpoint. Dùng `ticket_type` cho hạng vé, `sku` cho lựa chọn bán và mô tả giá theo hạng. Chạy sinh lại interface.
- [x] Admin tạo hạng, đặt giá, gán hạng cho khu và xem trước số SKU được tạo. Lưu cấu hình và sinh SKU trong cùng giao dịch. Gửi lại cùng cấu hình cho ra cùng tập SKU.
- [ ] Triển khai luồng tăng giá giữa đợt bán: tạm dừng giữ chỗ mới, lưu phiên bản giá cao hơn có hiệu lực ngay, rồi mở bán lại. Đơn đã giữ chỗ tiếp tục dùng giá đã chốt đến khi thanh toán hoặc hết hạn. Xem [thiết kế quản trị sự kiện](admin-event-unpublish.md).
- [x] Search, chi tiết sự kiện và sơ đồ ghế hiển thị hạng và giá có hiệu lực của lựa chọn bán. Giá thấp nhất được tính từ các lựa chọn đủ điều kiện mở bán.
- [x] Booking phân giải SKU từ ghế hoặc khu đã chọn, kiểm tra hạng, lấy giá hiệu lực và giữ tồn trong cùng giao dịch với việc tạo đơn. Chốt bán và hết hạn giữ chỗ theo dòng tồn hiện có.
- [x] Cập nhật đọc đơn, vé và phát vé để hiển thị đúng hạng, chỗ và số tiền đã chốt cho cả dữ liệu cũ và mới.

### Nghiệm thu

- [x] Dữ liệu mẫu có 25 ghế và 2 hạng tạo đúng 25 SKU, hai suất tạo đúng 50 dòng tồn.
- [x] Booking dùng đúng phiên bản giá trước và sau mốc đổi giá cho vé ngồi và vé đứng. Hai SKU ghế cùng hạng dùng chung lịch giá. Đơn trước đó giữ nguyên phiên bản giá và số tiền đã chốt.
- [x] Giữ cùng một ghế trên hai suất tạo hai đơn độc lập. Khi 50 người tranh cùng ghế trong một suất, đúng một giao dịch thành công.
- [x] Vé đứng giữ, hết hạn và chốt bán đúng số lượng. Test cạnh tranh và ràng buộc DB xác nhận `sold + held` không vượt `capacity`.
- [x] Chạy `./mvnw verify` ở CP1–CP4 với PostgreSQL qua Testcontainers. 1.012 test pass.
- [ ] Xác nhận toàn bộ `./mvnw verify` pass ở CP5. Lượt đầy đủ gần nhất pass 1.013/1.014 test. Test 1.000 lượt giữ ghế còn lỗi kết nối HTTP. Bộ nghiệm thu SKU chạy riêng sau khi bổ sung test hai suất pass 93/93.
- [x] Nghiệm thu backfill trên PostgreSQL bằng dữ liệu mô phỏng được tạo trước migration. CP4–CP5 giữ nguyên 130 dòng vé ngồi, 2 dòng vé đứng, 6 vé đã phát hành và 34 phiên bản giá, gồm 1 giá đã hẹn. Mọi dòng đơn có SKU sau backfill. Giao dịch thử đã rollback.
- [ ] Nghiệm thu backfill trên bản sao dữ liệu triển khai trước khi áp dụng migration. DB local trước thử nghiệm có 0 đơn, 0 vé và 0 giá đã hẹn.

Tham chiếu: [migration schema hiện tại](../src/main/resources/db/migration/V20261119_1400__shared_schema_v2.sql), [BookingRepository](../src/main/java/vingo/booking/internal/BookingRepository.java), [OpenAPI](../contracts/vingo-openapi.yaml).
