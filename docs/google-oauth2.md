# Đăng nhập Google trong Vingo

Vingo dùng **OAuth 2.0 Authorization Code + OpenID Connect (OIDC)**. Google xác thực danh tính người dùng. Backend Vingo dùng danh tính đó để tìm hoặc tạo tài khoản, sau đó cấp phiên riêng của Vingo. Frontend lấy access JWT của Vingo qua endpoint refresh để gọi API. [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect) · [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html)

## 1. Phạm vi triển khai

- Đổi contract Google từ `POST /api/v1/auth/google` nhận ID token sang luồng điều hướng qua `GET /api/v1/auth/google` và callback do Spring Security xử lý.
- Tạo tài khoản `BUYER` ở lần đăng nhập đầu, lưu Google `sub` và avatar nếu có. Lần sau dùng lại UUID Vingo theo `google_sub`.
- Tạo phiên trong PostgreSQL cho mỗi lượt đăng nhập thành công. Trình duyệt nhận refresh token qua cookie `HttpOnly`. Access JWT được cấp khi frontend gọi refresh.
- Xoay vòng refresh token khi refresh và thu hồi một phiên hoặc mọi phiên của tài khoản khi logout.
- Ghi mã lỗi callback trong log để phân biệt sự cố OAuth2 với lỗi kết nối trang frontend sau redirect.

[Contract OpenAPI](../contracts/vingo-openapi.yaml) · [Migration Google login](../src/main/resources/db/migration/V20261111_1030__auth_google_login.sql)

## 2. Sequence đăng nhập

```mermaid
sequenceDiagram
    actor Browser as Người dùng và trình duyệt
    participant OAuth as Spring Security và GoogleLoginHandler
    participant Redis as Redis
    participant Google as Google
    participant Login as GoogleLoginService
    participant DB as PostgreSQL
    participant API as AuthController và AuthService

    Browser->>OAuth: GET /api/v1/auth/google
    OAuth->>Redis: Kiểm tra giới hạn theo IP trước khi lưu state
    break Vượt hạn mức hoặc Redis không khả dụng
        OAuth-->>Browser: 429 với Retry-After hoặc 503
    end
    OAuth->>DB: Lưu yêu cầu OAuth2 và hash của cookie tạm, TTL 10 phút
    OAuth-->>Browser: 302 tới Google, đặt cookie tạm thời
    Browser->>Google: Đăng nhập và đồng ý chia sẻ thông tin cơ bản
    Google-->>Browser: 302 về callback với code hoặc lỗi, kèm state
    Browser->>OAuth: GET /api/v1/auth/oauth2/callback/google, gửi cookie tạm
    OAuth->>DB: Đọc và xóa yêu cầu khớp state và cookie, nếu còn hạn
    alt Yêu cầu OAuth2 không hợp lệ hoặc Google trả lỗi
        OAuth-->>Browser: 302 về web với google_login=failed
    else Yêu cầu OAuth2 hợp lệ và có authorization code
        OAuth->>Google: Đổi code bằng Client Secret và PKCE code verifier
        Google-->>OAuth: Kết quả đổi code
        OAuth->>OAuth: Xác minh ID token và nonce
        alt Google xác thực thành công
            OAuth->>Login: Truyền sub, email, email_verified, name, picture
            Login->>DB: Tìm theo google_sub, tạo user nếu cần và lưu hash refresh token
            alt Tài khoản được phép đăng nhập
                DB-->>Login: Hoàn tất transaction của user và session
                Login-->>OAuth: Refresh token Vingo và trạng thái tài khoản mới hay cũ
                OAuth-->>Browser: 302 về web với google_login=created hoặc success, đặt cookie refresh
                Browser->>API: POST /api/v1/auth/refresh với cookie và Origin
                API->>DB: Kiểm tra phiên, cập nhật hash refresh token
                API-->>Browser: 200 với access JWT và user, cập nhật cookie refresh
            else Email trùng hoặc tài khoản không đủ điều kiện
                Login-->>OAuth: Từ chối tạo phiên
                OAuth-->>Browser: 302 về web với google_login=email_taken hoặc failed
            end
        else Đổi code hoặc xác minh ID token thất bại
            OAuth-->>Browser: 302 về web với google_login=failed
        end
    end
```

Google trả người dùng về **callback của backend** trên cổng `8080`. Sau khi xử lý xong, backend chuyển tiếp người dùng đến **trang nhận kết quả của frontend**, mặc định là `http://localhost:3000/auth/google`. Giá trị `google_login` phục vụ hiển thị thông báo. Frontend xác nhận phiên bằng phản hồi `200` từ endpoint refresh.

## 3. Trách nhiệm của từng lớp

| Thành phần | Trách nhiệm |
|---|---|
| [`GoogleOAuth2SecurityConfig`](../src/main/java/vingo/auth/web/oauth2/GoogleOAuth2SecurityConfig.java) | Đăng ký Google client, bật PKCE, cấu hình hai đường dẫn OAuth2 và gắn success/failure handler. Filter chain này chỉ áp dụng cho trang bắt đầu và callback. |
| [`GoogleLoginStartFilter`](../src/main/java/vingo/auth/web/oauth2/GoogleLoginStartFilter.java) và [`GoogleLoginRateLimiter`](../src/main/java/vingo/auth/internal/GoogleLoginRateLimiter.java) | Giới hạn request bắt đầu theo IP bằng Redis trước khi Spring dọn hoặc lưu state trong PostgreSQL. |
| [`GoogleAuthorizationRequests`](../src/main/java/vingo/auth/web/oauth2/GoogleAuthorizationRequests.java) | Nối Spring Security với kho yêu cầu OAuth2. Tạo cookie tạm, chuyển yêu cầu OAuth2 thành dữ liệu lưu trữ và khôi phục yêu cầu khi callback quay về. |
| [`GoogleLoginHandler`](../src/main/java/vingo/auth/web/oauth2/GoogleLoginHandler.java) | Đọc claim từ `OidcUser` đã xác thực, gọi use case, đặt cookie refresh và redirect. |
| [`GoogleLoginService`](../src/main/java/vingo/auth/application/GoogleLoginService.java) | Kiểm tra điều kiện tài khoản, tìm hoặc tạo user, tạo session trong một transaction. |
| [`AuthController`](../src/main/java/vingo/auth/web/AuthController.java) và [`AuthService`](../src/main/java/vingo/auth/application/AuthService.java) | Nhận HTTP refresh/logout, xử lý vòng đời phiên, ký JWT và trả kết quả theo OpenAPI. |
| [`AuthRepository`](../src/main/java/vingo/auth/internal/AuthRepository.java) và [`GoogleAuthorizationRequestRepository`](../src/main/java/vingo/auth/internal/GoogleAuthorizationRequestRepository.java) | Thực hiện SQL trên `users`, `sessions` và `oauth_login_request`. |

Luồng nghiệp vụ đi theo **điểm vào HTTP → service → repository → PostgreSQL**. Callback OAuth2 đi vào qua Spring Security nên `GoogleLoginHandler` đảm nhận vai trò điểm vào. Kho trạng thái OAuth2 là hạ tầng của filter chain, được gọi qua adapter riêng. `AuthUser` là model nội bộ, còn các kiểu HTTP như `User` và `RefreshResult` được sinh từ OpenAPI.

## 4. Vì sao chọn cách này?

### Xác thực Google ở backend

Frontend điều hướng trình duyệt đến endpoint bắt đầu. Backend dùng Spring Security để quản lý authorization code, đổi code với Google, kiểm tra ID token và tạo `OidcUser`. Client Secret được đọc từ cấu hình backend. ID token và access token của Google phục vụ bước xác thực, rồi được bỏ sau callback. Scope `openid email profile` cung cấp danh tính, email và thông tin hồ sơ cần cho đăng nhập.

- **`state` và cookie tạm** gắn callback với lượt đăng nhập đã bắt đầu trong trình duyệt. Backend yêu cầu cả hai cùng khớp một yêu cầu còn hạn.
- **PKCE S256** gắn authorization code với code verifier mà backend giữ. Google kiểm tra verifier khi đổi code.
- **OIDC nonce** gắn ID token với yêu cầu đăng nhập ban đầu. Spring kiểm tra nonce trong quá trình xác thực.
- **PostgreSQL cho trạng thái OAuth2** cho phép một replica bắt đầu luồng và replica khác nhận callback. Bảng có TTL 10 phút. Callback hợp lệ lấy và xóa bản ghi bằng một câu SQL `DELETE … RETURNING`. Yêu cầu hết hạn bị bỏ qua khi đọc và được dọn khi lưu yêu cầu mới.
- **Giới hạn bắt đầu đăng nhập** mặc định là 10 lượt/IP trong 60 giây, điều chỉnh bằng `GOOGLE_LOGIN_RATE_LIMIT_PER_MINUTE`. Vượt hạn mức trả `429` kèm `Retry-After`. Nếu Redis không khả dụng, trang bắt đầu trả `503` để tránh tạo state khi bộ giới hạn ngừng hoạt động. Callback của lượt đã bắt đầu vẫn được xử lý. Ở deploy Compose, Tomcat lấy IP qua Caddy và các replica dùng chung Redis.

### Dùng Google `sub` để nhận diện tài khoản

`sub` là định danh ổn định của tài khoản Google. Email dùng để tạo hồ sơ và phát hiện xung đột với tài khoản Vingo hiện có. Quy tắc áp dụng sau khi Spring xác thực Google:

| Điều kiện | Kết quả |
|---|---|
| Thiếu `sub`, thiếu email hoặc email chưa xác minh | Từ chối tạo phiên. |
| Đã có `google_sub` và tài khoản là `BUYER` đang `ACTIVE` | Dùng lại UUID và tạo phiên mới. |
| Chưa có `google_sub` và email chưa được dùng | Tạo `BUYER` ở trạng thái `ACTIVE`. Email được chuẩn hóa chữ thường. Dùng email làm tên nếu Google thiếu tên. Avatar là URL HTTPS nếu có. |
| Email đã có chủ và `google_sub` chưa khớp | Trả `email_taken`. Người dùng cần xác thực tài khoản Vingo hiện có trước khi liên kết. Luồng liên kết là phần việc riêng. |
| Tài khoản liên kết đã bị khóa, xóa hoặc có role ngoài `BUYER` | Từ chối tạo phiên Google. |

Email và `google_sub` có unique index. Khi hai callback tạo tài khoản cùng lúc, `INSERT … ON CONFLICT DO NOTHING` rồi đọc lại theo `google_sub` giúp cả hai lượt dùng cùng một user. Tài khoản Google mới có `password_hash = NULL` vì họ đã xác thực bằng Google. Nếu Vingo bổ sung đăng nhập mật khẩu cho tài khoản này, người dùng có thể tự đặt mật khẩu sau khi xác thực lại.

### Tách access token và refresh token

| Dữ liệu | Lưu ở đâu và dùng khi nào |
|---|---|
| `users` | PostgreSQL lưu tài khoản, gồm `google_sub`, email, tên, role, trạng thái và `avatar_url` nếu có. |
| `oauth_login_request` | PostgreSQL lưu yêu cầu OAuth2 tạm thời. Bản ghi gồm hash để đối chiếu `state` và cookie, dữ liệu yêu cầu cần để khôi phục luồng và thời hạn. |
| Refresh token Vingo | Trình duyệt giữ giá trị gốc trong cookie `HttpOnly`. Bảng `sessions` lưu hash SHA-256, user ID, hạn phiên và thời điểm thu hồi. Token ngẫu nhiên có 32 byte trước khi mã hóa Base64 URL. |
| Access JWT Vingo | Frontend nhận qua JSON khi refresh. Thời hạn mặc định 15 phút. `sub` là UUID Vingo, `sid` là UUID phiên, `role` lấy từ DB. JWT được ký HS256 bằng cấu hình của Vingo. |

Cookie dùng `SameSite=Lax` để trình duyệt gửi cookie tạm khi Google điều hướng về callback. Ở cấu hình HTTPS, cookie có `Secure` và tiền tố `__Host-`. Profile `local` dùng cookie HTTP cho localhost. Cookie refresh là thông tin xác thực do trình duyệt tự gửi, nên nhánh refresh bằng cookie kiểm tra `Origin` khớp origin của `AUTH_FRONTEND_RETURN_URL`. CORS của refresh và logout cũng giới hạn theo origin này. Khi frontend và API chạy khác origin, dùng hai địa chỉ cùng site và gửi request với `credentials: 'include'`.

Phiên mặc định có hạn 30 ngày tính từ lúc đăng nhập. Mỗi lần refresh thay hash trong cùng bản ghi, giữ nguyên hạn phiên. Refresh token cũ mất hiệu lực sau lần xoay vòng thành công. Logout đặt `revoked_at` và xóa cookie. JWT đã cấp có thể dùng tới hạn riêng của nó, vì API xác thực JWT theo chế độ stateless.

## 5. API và cách frontend nối luồng

| API | Cách gọi | Kết quả |
|---|---|---|
| `GET /api/v1/auth/google` | Điều hướng trình duyệt tới endpoint này | `302` sang Google, `404` nếu tắt, `429` nếu vượt hạn mức hoặc `503` nếu Redis không khả dụng |
| `GET /api/v1/auth/oauth2/callback/google` | Google điều hướng trình duyệt về cùng code và state, hoặc thông tin lỗi | `302` về URL frontend với `google_login=created`, `success`, `email_taken` hoặc `failed` |
| `POST /api/v1/auth/refresh` | Web gửi cookie, body rỗng. Client API có thể gửi `{"refresh_token":"…"}` | `200` với `access_token`, `expires_in` và `user`. Với cookie, token refresh mới đi qua `Set-Cookie`. Với JSON, token mới nằm trong `refresh_token`. |
| `POST /api/v1/auth/logout` | Gửi bearer JWT của Vingo. Dùng `{"all_devices":true}` để thu hồi mọi phiên. | `204` khi thu hồi phiên và gửi lệnh xóa cookie |

Sau khi nhận `created` hoặc `success`, frontend gọi refresh. Ví dụ dưới đây chạy trên origin đã cấu hình cho frontend.

```javascript
const response = await fetch('http://localhost:8080/api/v1/auth/refresh', {
  method: 'POST',
  credentials: 'include',
});

if (!response.ok) {
  throw new Error(`Không thể làm mới phiên: HTTP ${response.status}`);
}

const { access_token, expires_in, user } = await response.json();
```

Frontend giữ access token trong bộ nhớ và gửi `Authorization: Bearer <access_token>` khi gọi API. Khi tải lại trang hoặc khi access token hết hạn, frontend có thể gọi refresh nếu cookie phiên vẫn còn hiệu lực.

## 6. Cấu hình và kiểm thử local

1. Trong [Google Auth Platform → Clients](https://console.cloud.google.com/auth/clients), chọn OAuth client loại **Web application**. Thêm `http://localhost:8080/api/v1/auth/oauth2/callback/google` vào **Authorized redirect URIs**.
2. Tạo `.env.local` ở thư mục gốc của backend, lưu UTF-8 không BOM. Git bỏ qua file này. Thay các giá trị mẫu bằng cấu hình của bạn.

   ```properties
   GOOGLE_LOGIN_ENABLED=true
   GOOGLE_CLIENT_ID=your-client-id
   GOOGLE_CLIENT_SECRET=your-client-secret
   GOOGLE_REDIRECT_URI=http://localhost:8080/api/v1/auth/oauth2/callback/google
   AUTH_FRONTEND_RETURN_URL=http://localhost:3000/auth/google
   ```

3. Khởi động PostgreSQL, Redis và backend từ PowerShell.

   ```powershell
   docker compose -f deploy/compose.dev.yaml up -d
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=api,local"
   ```

4. Khi frontend chưa chạy, dùng `AUTH_FRONTEND_RETURN_URL=http://localhost:8080/actuator/health` để quan sát tham số `google_login` trên thanh địa chỉ. JSON `status=UP` chỉ báo tình trạng backend.
5. Mở [trang bắt đầu đăng nhập Google](http://localhost:8080/api/v1/auth/google). Với email mới, mong đợi `created`. Đăng nhập lại cùng tài khoản để kiểm tra `success` và UUID Vingo giữ nguyên. Kiểm tra access token qua refresh, rồi gọi logout và xác nhận refresh token của phiên bị từ chối.

### Lệnh test tự động

```powershell
.\mvnw.cmd "-Dtest=GoogleLoginServiceTest" test
.\mvnw.cmd "-Dtest=GoogleLoginIntegrationTest" test
.\mvnw.cmd clean verify
```

Test tích hợp dùng PostgreSQL 18 và Redis để kiểm tra state, giới hạn tần suất, tạo tài khoản đồng thời, email trùng, refresh và logout. Test callback HTTP đi qua filter chain thật, dùng token endpoint và JWK Set mô phỏng trên loopback để đổi code, kiểm tra chữ ký và nonce. Có test riêng cho các đường dẫn Google khi tính năng tắt. Bước đổi code với Google thật được kiểm tra thủ công bằng trình duyệt.

### Lỗi local đã gặp

| Triệu chứng | Nguyên nhân hoặc cách kiểm tra |
|---|---|
| `NoClassDefFoundError: DomainException` khi khởi động | `target/classes` từng chứa bytecode lỗi. Dừng JAR đang chạy trước, chạy `.\mvnw.cmd clean package "-DskipTests"` rồi khởi động JAR mới. |
| `404` khi mở trang bắt đầu | Kiểm tra `GOOGLE_LOGIN_ENABLED=true`, profile `api,local` và mã hóa UTF-8 không BOM. BOM ở đầu file từng khiến Spring bỏ qua key đầu tiên. |
| `ERR_CONNECTION_REFUSED` tại cổng `3000` sau callback | Frontend chưa nghe ở địa chỉ trả về. Khởi động frontend hoặc dùng URL health như bước 4 để test backend riêng. |
| URL có `google_login=failed` | Xem dòng `Google OAuth2 callback rejected: reason=` hoặc `Google sign-in rejected: reason=` trong terminal. `authorization_request_not_found` gợi ý kiểm tra lượt đăng nhập còn hạn, URL bắt đầu và cookie trong cùng trình duyệt. Log chỉ ghi mã lỗi được chọn. |
| Google báo `redirect_uri_mismatch` | So sánh `GOOGLE_REDIRECT_URI` và URI trong Google Cloud, gồm scheme, host, port, path và dấu gạch chéo cuối. |
| Refresh báo `401` hoặc `403` | Với `401`, kiểm tra cookie còn hạn và phiên chưa bị thu hồi. Với `403`, kiểm tra `Origin` của frontend. Ở local, dùng `localhost` nhất quán cho frontend và API. |
