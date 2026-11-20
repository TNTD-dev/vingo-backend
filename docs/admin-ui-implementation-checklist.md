# Checklist triển khai Admin UI — 18/11/2026

## Phạm vi đã chốt

- BE và FE lấy `origin/develop`, triển khai trên `codex/admin-ui-alignment`.
- FE chính: `D:/Luan_document/InternVSF/frontend/vingo-web`.
- Bản làm trước được lưu bằng Git stash `backup-before-admin-ui-develop-sync-20261118` ở mỗi repo; không tự áp lại toàn bộ.
- Location là nguồn địa chỉ/tọa độ duy nhất; UI chỉ chọn điểm trên bản đồ.
- Thêm người cập nhật sân; số layout tính từ dữ liệu thật, chia nháp/đã công bố.
- Cây sơ đồ chọn loại FOOTBALL/CONCERT, sau đó chọn phiên bản layout tương ứng; không sửa hình học đã publish.
- Hoãn ảnh sân và truy vết file SVG nguồn tới zone. Cây hiển thị không phụ thuộc file nhập tạm.
- Không thêm membership, Super Admin, hoàn tiền, F&B hoặc luồng log máy chủ.
- Logs là nhật ký audit; activity feed chỉ hành động quản trị phù hợp, không đưa mọi lần đọc vào feed.

## Đồng bộ

- [x] Fetch BE/FE và xác nhận nhánh dev là develop.
- [x] Lưu thay đổi BE/FE hiện tại vào stash.
- [x] BE lấy develop `8fc4a1f`; FE lấy develop `81137ca`.

## Backend và contract

Các mục đánh dấu bên dưới là đã triển khai source, chưa xác nhận bằng chạy ứng dụng/test. Xem `admin-ui-runbook.md` để tự chạy và nghiệm thu.

- [x] Cập nhật OpenAPI trước khi viết endpoint; schema, validation, phân quyền và ví dụ cho API cây/người dùng.
- [x] Location: bỏ CityCode cố định, backfill dữ liệu cũ, thống nhất địa chỉ/tọa độ/tỉnh thành trong migration mới.
- [x] Di chuyển query catalog/search/booking/ticketing sang location.
- [x] Thêm updated_by, updated_by_name trong response; cập nhật cùng transaction khi sửa sân, kể cả luồng nhập.
- [x] API chi tiết sân: metadata, location, thời điểm, người sửa, số layout total/published/draft.
- [x] API cây layout theo loại, overview, phiên bản, khu; hàng/ghế dùng API chi tiết khu hiện có.
- [x] Người dùng: avatar, thống kê trạng thái, sort có cursor, lịch sử đăng nhập, export CSV giới hạn/chống formula injection.
- [x] Dashboard: KPI, kỳ trước cùng độ dài, doanh thu/vé tháng/ngày, cơ cấu hạng có nhãn, suất sắp diễn và tỷ lệ bán theo sân.
- [x] Khoảng [from,to), Asia/Ho_Chi_Minh, paid_at/starts_at theo metric.
- [x] Khôi phục audit có chọn lọc; thêm actor_name và bộ lọc actor/action/from/to/outcome/activity_only.
- [x] Ghi thành công Google vào lịch sử đăng nhập từ lúc triển khai; lịch sử cũ không được bịa/backfill.

## Frontend

- [x] Đồng bộ contract BE, sinh Orval; không sửa tay generated client.
- [x] Điều hướng thêm người dùng, sidebar sáng theo SVG; có CSS mobile. Chưa nghiệm thu hình ảnh/360px.
- [x] Chi tiết sân: metadata, bản đồ, thống kê layout, updated_by; không thêm ảnh/bãi xe.
- [x] Chọn FOOTBALL/CONCERT gọi API cây riêng theo loại, sau đó chọn phiên bản và xem khu.
- [x] Giữ preview/publish/import và cấu hình giá hiện có; không thêm kéo zone/X/Y/lưu hình học.
- [x] Giữ giới hạn một suất bóng đá có sẵn; giới hạn tạo mới theo FOOTBALL/CONCERT, vẫn hiển thị sự kiện lịch sử loại khác.
- [x] Người dùng: danh sách, lọc/sort/tải thêm, avatar, quyền/khóa có lý do, thống kê, lịch sử và export.
- [x] Tổng quan dùng API dashboard thật và ngày Việt Nam; không thêm số liệu giả/hoàn tiền.
- [x] Activity feed và Logs dùng API audit; không đọc log file server.
- [x] Không thêm membership/Super Admin/mã giả; danh sách người dùng dùng cursor tải thêm.

## Kiểm tra và bàn giao

- [ ] Migration: DB mới và dữ liệu sân cũ thiếu location, không bịa tọa độ hoặc địa phương.
- [ ] Test API: dữ liệu, validation, quyền ADMIN/BUYER, phân trang, CSV, cây theo loại/phiên bản.
- [ ] Test KPI: biên giờ Việt Nam, không đếm trùng suất/hạng, tính cả chỗ đứng.
- [ ] Người dùng tự chạy BE mvnw verify; FE npm run check theo yêu cầu không tự chạy của agent.
- [ ] Kiểm tra UI desktop/mobile và luồng tạo sân → chọn bản đồ → nhập/publish layout → sự kiện/giá → báo cáo/log.
- [ ] Ghi kết quả thực tế, phần chưa hoàn thành và giới hạn; chưa deploy/push khi chưa được yêu cầu.
