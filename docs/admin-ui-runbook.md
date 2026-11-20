# Bàn giao Admin UI — 18/11/2026

## Nền triển khai

- Backend: `D:/Luan_document/InternVSF/backend/vingo-backend`, nhánh `codex/admin-ui-alignment`, nền develop `8fc4a1f`.
- Frontend: `D:/Luan_document/InternVSF/frontend/vingo-web`, nhánh `codex/admin-ui-alignment`, nền develop `81137ca`.
- Thay đổi trước đó: Git stash `backup-before-admin-ui-develop-sync-20261118` ở mỗi repo. Dùng `git stash list` và `git stash show` để xem; không tự áp toàn bộ stash lên bản mới.
- Không thay worktree `D:/Luan_document/InternVSF/backend/vingo-web`.
- Chưa commit, push hoặc deploy. Migration chưa được chạy lên DB.

## Kiểm tra do người dùng thực hiện

Theo yêu cầu, agent chỉ sinh interface Java/Orval và định dạng file FE. Chưa chạy test, compile/build, typecheck, lint hoặc verify. Mã test đã được bổ sung/cập nhật nhưng chưa có kết quả thực thi. Chưa kiểm tra giao diện trong trình duyệt/mobile.

Backend (Docker đang chạy, có Java 25):

```powershell
cd D:/Luan_document/InternVSF/backend/vingo-backend
.\mvnw.cmd verify
```

Frontend (Node đáp ứng engines trong package.json):

```powershell
cd D:/Luan_document/InternVSF/frontend/vingo-web
npm.cmd run generate
npm.cmd run check
```

Nếu chưa có dependencies, chạy `npm.cmd ci` trước. Bản contract FE đang là bản sao local từ BE chưa commit; chưa chạy `contract:sync` để tránh ghi đè bằng contract remote cũ.

## Thay đổi schema cần lưu ý trước khi chạy ứng dụng

### DB local mới sau khi đồng bộ develop

Đã tạo DB `vingo_admin_ui_20261118` trên PostgreSQL local và đặt `DB_URL=jdbc:postgresql://localhost:5432/vingo_admin_ui_20261118` trong `.env.local` (file bị Git bỏ qua). DB `vingo` cũ và dữ liệu của nó vẫn được giữ nguyên. Đã chạy riêng Flyway thành công với 32 migration, đến `20261118.1600`, để tạo schema và thêm tài khoản `admin@luannt.com` có role `ADMIN`, status `ACTIVE`. Mật khẩu được lưu dạng Argon2, không ghi vào tài liệu. Chưa khởi động ứng dụng hay chạy test.

DB cũ thiếu migration `20261114.1000` và `20261115.1000` dù đã chạy đến `20261117.0200`. Không bật `outOfOrder` cho DB cũ: migration 15/11 dùng `showtime.hall_id`, cột đã bị migration 16/11 xóa. DB mới cho phép Flyway chạy toàn bộ migration theo thứ tự từ đầu.

Khởi động API bằng PowerShell:

```powershell
cd D:/Luan_document/InternVSF/backend/vingo-backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=api,local"
```

Worker cũng cần profile `local` để đọc cùng `.env.local`:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=worker,local"
```

DB mới có tài khoản admin nêu trên và đã được nạp bộ dữ liệu DEMO ngày 19/11/2026: 7 địa điểm, 26 layout, 12 sự kiện, 14 suất, 32 người dùng mẫu, 166 đơn và 320 vé. Xem [chi tiết dữ liệu và cách xem giao diện](admin-ui-demo-data.md). Không dùng `scripts/seed.sql` cũ. Nếu IDE hoặc terminal đã đặt biến môi trường `DB_URL` hay tham số `spring.datasource.url`, cần đổi chúng sang DB mới vì chúng có thể ghi đè `.env.local`. Các phiên API/worker đang chạy trước đó cần được khởi động lại để dùng cấu hình mới. Việc chuyển về DB `vingo` chỉ đổi kết nối, không sửa được lịch sử Flyway đang thiếu ở DB cũ.

### Migration địa điểm

Migration `V20261118_1600__catalog_location_source_of_truth.sql`:

1. Gộp địa chỉ/tọa độ cũ vào `location`, ưu tiên khóa có sẵn trong location.
2. Không bịa tọa độ, không tự đặt người sửa cho dữ liệu lịch sử.
3. Bỏ các cột vật lý `address`, `city`, `district`, `lat`, `lng`; thêm `updated_by`.
4. Tạo hàm/index tìm địa phương và FTS từ location.

Backup DB trước khi khởi động API trên DB đang dùng. Backend cũ không tương thích sau migration này. Chạy BE/FE mới cùng đợt. Các trường địa chỉ phẳng ở API còn được giữ để tương thích client đọc, nhưng chỉ được suy ra từ location. Đầu vào cũ cũng được chuyển thành location; UI admin mới chọn vị trí bằng bản đồ.

Tọa độ của dữ liệu cũ có thể thiếu; khi sửa sân, chọn lại vị trí để xác nhận. Cần cấu hình `GEOAPIFY_API_KEY` ở backend cho reverse geocode; khóa tile công khai `VITE_GEOAPIFY_TILE_KEY` là tùy chọn. Không đặt khóa backend trong VITE_*.

Script `scripts/seed.sql` có cấu trúc hall cũ từ trước đợt này; không dùng nó để seed schema mới. Testcontainers sử dụng fixture riêng đã được điều chỉnh ở những chỗ ghi cột địa chỉ cũ.

## API mới/mở rộng

- `GET /api/v1/admin/venues/{venue_id}`: location, metadata, thời điểm, người sửa, số layout nháp/đã công bố.
- `GET /api/v1/admin/venues/{venue_id}/layout-tree?layout_type=FOOTBALL|CONCERT`: phiên bản giảm dần, overview và zone; cursor/limit. Ghế/hàng dùng API zone có sẵn khi mở khu.
- `GET /api/v1/admin/users?sort=newest|oldest|name`: avatar, sort và cursor tương ứng.
- `GET /api/v1/admin/users/statistics`: số tài khoản theo trạng thái lưu; ACTIVE không có nghĩa đang online.
- `GET /api/v1/admin/users/export`: CSV theo bộ lọc, giới hạn 10000 và chống formula injection.
- `GET /api/v1/admin/dashboard?from=...&to=...&group_by=day|month`: KPI, kỳ trước cùng độ dài, biểu đồ, cơ cấu hạng có tên sự kiện, suất sắp diễn, tỷ lệ bán theo sân.
- `GET /api/v1/admin/audit-logs`: thêm actor/action/from/to/outcome/activity_only và tên admin. Activity feed dùng `activity_only=true`, không phải log máy chủ.

Tất cả endpoint trên yêu cầu ADMIN. Khoảng báo cáo là [from,to), ngày/tháng theo Asia/Ho_Chi_Minh. Snapshot suất sắp diễn và đang mở bán tính tại computed_at, không theo khoảng lọc. Số liệu bán theo trạng thái hiện tại; không phải báo cáo đối soát ngân hàng hay snapshot lịch sử.

## Smoke test thủ công

1. Đăng nhập admin; tạo sân qua bản đồ ở một tỉnh ngoài HN/HCM/DN; mở lại kiểm tra location, người sửa và timestamp.
2. Nhập layout FOOTBALL và CONCERT; chọn từng loại để xác nhận không lẫn phiên bản; kiểm tra trạng thái riêng của overview/layout và số đếm.
3. Publish layout hợp lệ; tạo sự kiện đúng loại và gán hạng giá theo khu. Không sửa hình học trên layout đã công bố.
4. Mở người dùng: lọc/sort/tải thêm, avatar, đổi quyền/khóa có lý do, lịch sử quản trị và CSV.
5. Dashboard: đổi khoảng ngày/nhóm tháng, đối chiếu tổng với báo cáo và giao dịch PAID; kiểm tra suất/khu đứng/công suất.
6. Logs: lọc admin, thời gian, kết quả; feed không lẫn request GET hoặc thao tác lỗi.
7. Kiểm tra desktop và chiều rộng 360px; không dùng số minh họa để nghiệm thu dữ liệu thật.

## Phần hoãn

- Ảnh sân, bãi xe mới, truy vết file nguồn–zone lâu dài, kéo/thả chỉnh hình học, membership, Super Admin, hoàn tiền, log máy chủ.
- Liên kết file–zone là nguồn gốc SVG của từng zone trong một phiên bản, khác cây chức năng theo loại layout. Cây đã lưu không phụ thuộc file nhập tạm.
