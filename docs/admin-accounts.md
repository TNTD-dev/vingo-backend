# Quản lý tài khoản người dùng

## Giao diện

Admin mở **Hệ thống → Tài khoản người dùng** tại `/admin/users` trên web hiện tại. Chức năng này không thực hiện kế hoạch tách domain portal.

- Tìm theo tên, email hoặc số điện thoại; lọc quyền và trạng thái; phân trang.
- Xem chi tiết tài khoản, tình trạng khóa và thời hạn khóa tạm.
- Cấp/thu hồi quyền ADMIN hoặc khóa/mở khóa sau khi xác nhận và nhập lý do.
- Không sửa thông tin cá nhân, mật khẩu hoặc xóa tài khoản qua trang quản trị này.

## API

Tất cả endpoint yêu cầu `Authorization: Bearer <access_token>` với role ADMIN.

| Method | URL | Kết quả |
|---|---|---|
| GET | `/api/v1/admin/users?q=&role=&status=&cursor=&limit=20` | `{ data: AdminAccount[], next_cursor }` |
| GET | `/api/v1/admin/users/{user_id}` | `AdminAccount` |
| PATCH | `/api/v1/admin/users/{user_id}` | `AdminAccount` sau cập nhật |

Body cấp quyền:

```json
{ "role": "ADMIN", "reason": "Phân công quản lý sự kiện" }
```

Body thu hồi quyền:

```json
{ "role": "BUYER", "reason": "Kết thúc phân công quản trị" }
```

Body khóa hoặc mở khóa:

```json
{ "locked": true, "reason": "Tạm ngừng truy cập" }
```

Thay `locked` bằng `false` để mở khóa. Có thể gửi đồng thời `role` và `locked`. `reason` bắt buộc, không chỉ có khoảng trắng, tối đa 500 ký tự.

`AdminAccount` chỉ có ID, tên, email, điện thoại, role, status, cờ khóa quản trị, cờ đã có mật khẩu, thời hạn khóa tạm và ngày tạo. Không trả hash mật khẩu, Google subject hoặc token.

## Quy tắc

- Chỉ thay đổi tài khoản đã xác minh, ở trạng thái ACTIVE hoặc LOCKED. Không sửa UNVERIFIED/DELETED.
- Không tự hạ quyền hoặc tự khóa. Giữ ít nhất một ADMIN hoạt động; các cập nhật quyền/khóa được tuần tự hóa bằng transaction advisory lock.
- Google login hiện chỉ cho BUYER. Vì vậy tài khoản phải đặt mật khẩu trước khi được cấp ADMIN; không mở thêm luồng Google ADMIN trong thay đổi này.
- Khóa quản trị đặt `admin_locked=true`, `status=LOCKED`, không tự hết hạn. Mở khóa trả về ACTIVE và xóa hạn khóa tạm.
- Đặt lại mật khẩu không gỡ khóa quản trị.
- Gọi lại cùng trạng thái không thu hồi thêm phiên hoặc tạo thêm audit.
- Thay đổi thực tế cập nhật users, tăng auth_version, thu hồi tất cả refresh sessions và ghi audit UPDATE_ACCOUNT cùng transaction.
- Audit lưu actor, lý do, ID tài khoản và role/status/admin_locked trước/sau; không đưa thông tin xác thực vào audit.

## Vô hiệu token cũ

JWT mới có claim `av` là auth_version của user. Khi xác thực API, backend đọc trạng thái user hiện hành và đối chiếu role/version; tài khoản không ACTIVE hoặc bị khóa quản trị bị từ chối.

Token trước thay đổi chưa có `av` được xem là version 0, nên vẫn hoạt động nếu tài khoản chưa bị thay đổi. Sau khi đổi quyền/khóa, token cũ trả 401 ngay ở request tiếp theo. Mở khóa không khôi phục hiệu lực token cũ. Người dùng phải đăng nhập lại.

Việc kiểm tra này thêm một truy vấn PostgreSQL cho mỗi request có JWT; không dùng cache có thể giữ quyền cũ. Request hoặc SSE đã bắt đầu trước khi thay đổi không bị hủy giữa chừng bởi validator.

## Migration và review

- Migration mới: `V20261116_1800__auth_admin_accounts.sql`; không thay đổi migration lịch sử.
- Bổ sung users.admin_locked, users.auth_version và action UPDATE_ACCOUNT vào constraint audit.
- Contract backend là nguồn cho phần API tài khoản; frontend copy phần bổ sung và sinh lại Orval. Các chênh lệch contract có sẵn ngoài tính năng này được giữ nguyên.
- Cần review contract theo CLAUDE.md và phối hợp review các phần auth, security, audit và frontend admin.

## Kết quả kiểm tra trong workspace

- Frontend: typecheck, build và 343 test đều đạt; lint/format các file thay đổi đạt.
- `npm run check` toàn repo dừng ở format của 302 file có sẵn; không format lại các phần ngoài phạm vi.
- `mvnw verify` thông thường chưa compile được do `VenuePlaceLookupService` đang dở, tham chiếu hai file client rỗng `GooglePlacesClient` và `GeminiParkingFeeClient` trong workspace.
- Đã dùng POM kiểm tra tạm chỉ loại service chưa hoàn thiện đó khỏi compile, không bỏ test: lượt toàn bộ chạy 792 test, một test đếm query bị ảnh hưởng bởi bước xác thực DB mới. Sau khi cập nhật kỳ vọng query, chạy lại 18 test gồm AdminAccountIntegrationTest, PasswordLoginIntegrationTest, BookingCorrectionsTest và ModularityTest đều đạt, không skip.
- POM kiểm tra tạm được xóa sau khi chạy; không thay đổi hay xóa các file tra cứu địa điểm đang dở. Chưa xác nhận verify tiêu chuẩn toàn repo hoặc kiểm tra Swagger staging đạt.
