# Auth hiện tại và kế hoạch tách admin portal

## 1. Auth hiện tại

- `POST /api/v1/auth/login` xác thực email/SĐT và mật khẩu bằng Argon2, kiểm tra trạng thái tài khoản và giới hạn thử sai. Tạo session, lưu hash refresh token, cấp JWT và trả user có role.
- Controller trả access token, refresh token, expires_in, user trong JSON, đồng thời đặt cookie refresh HttpOnly.
- JWT có `sub` là user ID, `role` là BUYER/ADMIN, `sid` là session ID; hạn access token cấu hình 15 phút.
- Google login dùng redirect/callback OIDC, chỉ chấp nhận tài khoản BUYER ACTIVE; callback đặt cookie refresh và chuyển về frontend. Frontend gọi refresh để lấy JWT Vingo.
- Frontend giữ token trong bộ nhớ. Khi khởi động lại trang, thử refresh bằng cookie; trong phiên đăng nhập mật khẩu, hiện có thể refresh bằng token trong JSON.
- Refresh kiểm tra phiên và xoay vòng token. Cookie refresh yêu cầu Origin khớp một origin duy nhất suy ra từ AUTH_FRONTEND_RETURN_URL.
- Cookie production có HttpOnly, Secure, SameSite=Lax và tiền tố __Host-, không có Domain; cookie thuộc host nhận response.
- SecurityConfig xác minh JWT HS256. API admin và POST checkins chỉ cho ADMIN; các API còn lại áp dụng quyền public hoặc BUYER/ADMIN. Service tiếp tục kiểm tra chủ sở hữu dữ liệu.
- Logout thu hồi refresh session và xóa cookie. Access JWT đã cấp chưa bị thu hồi ngay ở mỗi request, có thể còn dùng đến hạn.
- UI hiện có `/login` và `/admin/login` chung ứng dụng/domain. AdminLoginPage gọi chung API login, kiểm tra user.role rồi điều hướng nội bộ tới /admin.
- Nếu BUYER đăng nhập trang admin, UI hiện báo lỗi nhưng session BUYER đã được tạo và đưa vào token store; cần xử lý trạng thái này khi sửa.

## 2. Mục tiêu và giả định triển khai

Các địa chỉ sau là ví dụ, chưa phải domain đã được xác nhận:

| Vai trò | Production minh họa | Local minh họa |
|---|---|---|
| Buyer web | https://www.vingo.example | http://localhost:3000 |
| Admin portal | https://portal.vingo.example | http://localhost:3001 |
| API chung | https://api.vingo.example | http://localhost:8080 |

Production dùng các subdomain HTTPS cùng domain gốc. Phương án này tách giao diện, vẫn dùng chung backend, database, hệ thống tài khoản và phiên xác thực.

Không giả định có hai hệ thống auth độc lập. Nếu cần phiên admin/buyer độc lập hoặc hai domain gốc khác nhau, phải thiết kế riêng cookie/session hoặc cơ chế chuyển phiên; không chỉ áp dụng cấu hình bên dưới.

## 3. Luồng đích

### Đăng nhập trực tiếp trên portal

1. Mở portal, nếu chưa có phiên thì hiển thị trang login.
2. Portal gọi API login chung với credentials include.
3. API xác thực và đặt cookie refresh trên host API.
4. Frontend kiểm tra role: ADMIN vào dashboard; BUYER không được render hoặc gọi luồng quản trị, xóa trạng thái token trong portal và hiển thị thông báo phù hợp.
5. Tải lại portal: gọi refresh với cookie trên cùng host API, nhận JWT và kiểm tra role trước khi render.

### ADMIN đăng nhập từ trang buyer

1. Buyer web gọi cùng API login và nhận role ADMIN.
2. Chuyển trang bằng window.location.replace đến portal origin đã cấu hình, không dùng điều hướng React Router cho việc đổi domain.
3. Portal không nhận token trong URL. Portal gọi refresh tới API chung với credentials include, dùng cookie được API đặt ở bước login.
4. Refresh trả user ADMIN và JWT mới; portal hiển thị dashboard.
5. BUYER đăng nhập vẫn ở buyer web.

### Truy cập link quản trị cũ

- `/admin/**` trên buyer web chuyển tới portal với đường dẫn hợp lệ tương ứng.
- Rà cả `/checkin` và các route admin copilot để không còn màn hình quản trị được phục vụ ở buyer web.
- Chỉ nhận next là đường dẫn nội bộ hợp lệ trong danh sách route portal. Không nhận origin tùy ý từ query; không tạo vòng chuyển hướng.
- Khi buyer web phát hiện phiên ADMIN qua refresh lúc tải trang, áp dụng cùng chính sách chuyển sang portal và dừng hoạt động refresh nền tại buyer web.

## 4. Backend và chính sách cookie/CORS

- Tách cấu hình frontend buyer, portal và allowed browser origins. Không dùng URL callback Google làm nguồn duy nhất cho danh sách Origin hợp lệ.
- Cho phép chính xác các origin đã cấu hình, không dùng wildcard khi có credentials.
- Cấu hình CORS cho toàn bộ nhóm API mà hai frontend gọi, gồm preflight, Authorization, Content-Type, Idempotency-Key và các header response cần đọc theo contract.
- Cho phép credentials trên những endpoint cần cookie; frontend phải gửi credentials include khi login, refresh, logout và các luồng auth đặt/đọc cookie.
- Kiểm tra Origin cookie refresh dựa trên allowlist buyer/portal thay vì so sánh một giá trị. Rà các endpoint thay đổi phiên qua cookie, gồm login/logout, để chính sách Origin nhất quán.
- Giữ cookie host-only trên API; không mở Domain ra toàn bộ domain gốc. Cả hai frontend gọi cùng API host để dùng phiên; không proxy auth qua hai host frontend khác nhau trong production của phương án này.
- Giữ SameSite=Lax cho topology cùng site qua HTTPS. Hai domain gốc khác nhau cần quyết định lại chiến lược, không tự đổi SameSite rồi giả định trình duyệt sẽ chấp nhận cookie bên thứ ba.
- Google login tiếp tục phục vụ BUYER; callback Google về buyer web. Chưa thêm Google login cho ADMIN.
- Giữ phân quyền ADMIN trên backend. CORS và domain portal không thay thế kiểm tra quyền bằng JWT.
- Không cần migration database cho phạm vi dùng chung session. Nếu muốn chặn BUYER trước khi tạo session portal, cần thiết kế endpoint/policy login portal riêng và sửa contract trước; không tin role do client gửi.
- Nếu sửa endpoint/model/description, sửa OpenAPI trước và sinh lại code theo CLAUDE.md.

## 5. Frontend và triển khai

- Trước khi sửa repo vingo-web, đọc hướng dẫn áp dụng tại repo đó.
- Có thể giữ chung source, tạo hai entry/build mode buyer và portal, với route tree riêng. Không bắt buộc tạo repo mới.
- Thêm cấu hình portal URL, buyer URL và API base URL cho từng môi trường.
- Buyer build không đăng ký màn hình admin; chỉ giữ redirect cho URL cũ cần tương thích.
- Portal đăng ký login, dashboard, catalog, đơn hàng, checkin và copilot quản trị; route guard chờ hoàn tất bootstrap auth.
- Sau login hoặc refresh kiểm tra role trước điều hướng/render; xử lý rõ trạng thái BUYER tại portal mà không tạo vòng redirect.
- Chuyển browser flow sang refresh cookie để không cần chuyển token giữa origin; access token vẫn giữ trong bộ nhớ.
- Hiện refresh được gộp trong một tab nhưng chưa phối hợp nhiều tab/origin. Do refresh rotation dùng chung cookie, kiểm tra race giữa buyer và portal; xử lý retry/bootstrap có giới hạn khi refresh cũ thua cuộc, không tự xóa cookie mới từ request thất bại. Kiểm tra lại hành vi backend trước khi chốt giải pháp.
- Logout hiện thu hồi session chung: portal và buyer cùng dùng phiên đó sẽ không refresh được tiếp; JWT cũ vẫn có thể dùng đến hết hạn. UI ở tab khác cập nhật khi kiểm tra phiên hoặc API thất bại.
- Local dùng hai port và API chung; cấu hình cookie HTTP local phù hợp, không nhầm localhost với 127.0.0.1.
- Cấu hình DNS, TLS, SPA fallback, API reverse proxy và CORS cho domain thật. Không đổi domain OAuth callback/VNPay ngoài phạm vi cần thiết.

## 6. Kiểm tra và điều kiện hoàn tất

- [ ] ADMIN đăng nhập ở buyer web chuyển sang portal mà không đăng nhập lần hai.
- [ ] ADMIN đăng nhập trực tiếp portal, refresh trang vẫn giữ phiên.
- [ ] BUYER ở lại buyer web, không truy cập được màn hình/API admin.
- [ ] Phiên BUYER vào portal được xử lý rõ ràng, không còn token store admin giả hoặc vòng redirect.
- [ ] Google BUYER login vẫn hoạt động.
- [ ] Link /admin cũ, checkin và copilot đi đúng portal; next không cho redirect ngoài danh sách cho phép.
- [ ] Origin buyer và portal hợp lệ gọi được API; origin lạ bị từ chối trong browser flow cần cookie.
- [ ] Không có token trong URL, localStorage hoặc cơ chế chuyển domain.
- [ ] Refresh rotation được kiểm tra khi nhiều request/tab hoạt động đồng thời, lúc chuyển domain và khi logout.
- [ ] Giữ phản hồi 401 khi chưa xác thực, 403 khi thiếu quyền và kiểm tra chủ sở hữu hiện tại.
- [ ] Chạy kiểm tra frontend phù hợp và ./mvnw verify backend theo quy định repo.

## 7. Thứ tự thực hiện

1. Xác nhận domain thật, topology cùng site và yêu cầu dùng chung phiên.
2. Cập nhật tài liệu/contract nếu có thay đổi API; chuẩn bị config backend, CORS và Origin checks.
3. Tách route/build frontend, cấu hình credentials và luồng chuyển ADMIN sang portal.
4. Xử lý bootstrap/refresh/logout và kiểm tra race của phiên dùng chung.
5. Kiểm tra local hai origin, sau đó staging HTTPS cùng domain gốc.
6. Triển khai cấu hình API, buyer và portal đồng bộ; giữ redirect link quản trị cũ.

Chưa thay đổi code thực thi hoặc cấu hình domain trong kế hoạch này.
