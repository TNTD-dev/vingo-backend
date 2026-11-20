# Nhật ký hoạt động admin

Mọi request của người dùng ADMIN đến `/api/v1/admin/**` và `/api/v1/checkins`
được ghi đồng bộ vào `audit_log` với action `ADMIN_ACTIVITY`. Bao gồm xem,
tìm kiếm, xuất dữ liệu, tạo/sửa/xóa sân, sự kiện, suất diễn, cấu hình giá,
sơ đồ ghế, phiên nhập, khóa/mở ghế, quản lý tài khoản, vé, đơn và báo cáo.
Route mới dưới `/admin/**` tự động được bao phủ.

- `actor_id`: admin thực hiện, lấy từ `CurrentUser`.
- `entity=admin_activity`, `entity_id=actor_id`: nhóm các lần gọi theo admin.
- `at`: thời điểm bắt đầu, lấy từ Clock.
- `after.operation`: tên method controller, tương ứng operationId trong contract.
- `after.method`, `after.route`: HTTP method và route mẫu.
- `after.target_ids`: các ID UUID trên đường dẫn.
- `after.result_ids`: các ID UUID cho phép trong kết quả, bao gồm ID đối tượng mới tạo.
- `after.ticket_code`: mã vé công khai nếu kết quả soát vé có mã vé, không phải QR token.
- `after.request_id`: mã đối chiếu với log ứng dụng.
- `after.outcome`: STARTED, SUCCEEDED hoặc FAILED; `after.status`: kết quả HTTP.

Một dòng được commit trước khi chạy controller, sau đó cập nhật kết quả trước
khi ghi response body. Response rỗng có interceptor hoàn tất dự phòng.
Nếu tiến trình dừng trước khi hoàn tất, dòng STARTED vẫn còn để truy vết;
STARTED không chứng minh thao tác đã thành công. HTTP thành công thể hiện
kết quả API, ví dụ gửi lại vé là đã đưa vào outbox, chưa phải email đã gửi.
Nhật ký request có transaction riêng; không thay đổi transaction nghiệp vụ.
Audit nghiệp vụ trước/sau đang có tiếp tục chạy chung transaction với thay đổi.

Xem lịch sử một admin qua API hiện có:
`GET /api/v1/admin/audit-logs?entity=admin_activity&entity_id=<admin UUID>`.
Tên admin có thể tra từ `actor_id` qua API tài khoản hiện có.

Không lưu body, query string, header Authorization, QR, mật khẩu hay nội dung
response. Request bị Spring Security từ chối trước MVC, URL không có handler,
các API public/buyer và lời gọi service ngoài HTTP không thuộc nhật ký này.
Thao tác không thực sự thay đổi dữ liệu vẫn được ghi nếu admin gọi API đó.
