# Dữ liệu mẫu Admin UI

Đã nạp vào DB local `vingo_admin_ui_20261118` ngày 19/11/2026. Không thay DB `vingo` cũ hay mật khẩu của `admin@luannt.com`.

## Các màn để xem

| Màn | Dữ liệu mẫu |
| --- | --- |
| Tổng quan `/admin` | Doanh thu/vé trong khoảng 60 ngày gần thời điểm seed, kỳ trước, hạng giá, suất sắp diễn, công suất và hoạt động admin |
| Sân `/admin/venues` | 7 địa điểm: 5 sẵn sàng, 1 chỉ có nháp, 1 chưa có sơ đồ; 3 loại STADIUM/THEATER/OUTDOOR; HN/HCM/DN/VN-HP/VN-ND |
| Chi tiết sân | Location, tọa độ minh họa, người sửa, timestamp và số layout |
| Cây sơ đồ | 26 layout. Mỗi sân trong 5 sân đầu có cùng một overview đã publish, 2 FOOTBALL publish + 1 FOOTBALL draft, 1 CONCERT publish + 1 CONCERT draft |
| Bản đồ sân/suất | SVG tổng thể, các khu ngồi A/B, hàng A–F và số ghế; concert có thêm 180 chỗ đứng GA; một suất bóng đá có 4 ghế BLOCKED |
| Sự kiện `/admin/events` | 12 sự kiện, 14 suất: 8 PUBLISHED, 2 ENDED, 1 DRAFT, 1 CANCELLED; có đang bán, chưa mở bán và đã đóng bán |
| Người dùng `/admin/users` | 32 tài khoản DEMO cộng admin thật: ACTIVE/UNVERIFIED/LOCKED/DELETED, avatar, lịch sử đăng nhập mô phỏng, hai admin mẫu để phân biệt người thao tác |
| Đơn `/admin/orders` | 166 đơn: 144 PAID, 8 PENDING, 5 CANCELLED, 5 EXPIRED, 4 NEEDS_REVIEW; mã D00001…D00166 |
| Vé `/admin/tickets` | 320 vé DEMO, có ISSUED/USED/VOID, vé ngồi và đứng, dữ liệu check-in của suất đã diễn ra |
| Logs `/admin/audit-logs` | 36 ADMIN_ACTIVITY mô phỏng, gồm thành công/thất bại, và 1 audit khóa tài khoản; feed chỉ chọn write thành công |

Ảnh poster lấy từ ảnh mẫu đã có trong FE, avatar SVG được dựng local; các asset nằm trong `public/demo/admin-ui/` của FE chính. Không phụ thuộc dịch vụ ảnh bên ngoài. Dữ liệu này phục vụ giao diện local; các URL ảnh tương đối cần frontend dev server phục vụ cùng origin.

## Cách xem

1. Chạy BE với profile `api,local`, kết nối DB mới; chạy FE bằng `npm.cmd run dev`, `VITE_USE_MOCK=false`.
2. Đăng nhập bằng `admin@luannt.com`, tải lại trang để React Query lấy dữ liệu mới.
3. Chọn khoảng 30 ngày gần nhất để xem dashboard, hoặc mở rộng 60 ngày để xem toàn bộ doanh số mẫu. Mốc ngày được tính tại lúc seed, không tự dịch chuyển mỗi ngày.
4. Tài khoản DEMO có email `demo.01@example.test` đến `demo.32@example.test`; lúc seed chúng nhận cùng hash mật khẩu của admin hiện tại. Đây không phải email nhận thư thật. Chưa đặt số điện thoại/xác minh điện thoại cho buyer DEMO, nên muốn test giữ ghế thực tế cần thực hiện luồng xác minh của ứng dụng.

Đơn PENDING có hạn giữ khoảng 9 phút sau seed; worker có thể chuyển chúng sang EXPIRED và nhả tồn kho. Không tạo payment intent OPEN hoặc outbox gửi thư để các worker không gọi cổng thanh toán/gửi email cho fixture. Các payment SUCCEEDED và audit/login có sẵn là dữ liệu mô phỏng, không chứng minh giao dịch VNPay hay hành động HTTP thật đã xảy ra. Audit DEMO có `reason` và snapshot đánh dấu; không thêm lịch sử đăng nhập giả cho admin thật.

SVG được dựng trực tiếp theo schema hiện tại và dùng chứng nhận `local-demo-ui-seed` cho fixture. Không dùng bộ dữ liệu này để nghiệm thu validator, quy trình nhập SVG hoặc luồng thanh toán thật. Chưa chạy UI trong trình duyệt, build, test hay verify; người dùng tự nghiệm thu giao diện.

## Nạp lại trên DB mới tương ứng

```powershell
cd D:/Luan_document/InternVSF/backend/vingo-backend
Get-Content -LiteralPath scripts/seed-admin-ui.sql -Raw | docker compose -f deploy/compose.dev.yaml exec -T postgres psql -U vingo -d vingo_admin_ui_20261118 -v ON_ERROR_STOP=1
```

Script khóa theo dataset, chạy trong transaction, giới hạn tên DB và không xóa/ghi đè bản ghi. Chạy lại không nhân đôi hay đặt lại thời gian của bộ dữ liệu đã có. Giữ nguyên dữ liệu bạn sửa trên UI; không dùng script `seed.sql` cũ để reset bộ này.
