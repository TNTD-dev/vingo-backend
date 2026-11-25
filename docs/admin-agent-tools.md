# Thiết kế API admin và tool cho Vingo Agent

Đối chiếu contract và controller tại workspace ngày 20/11/2026. Đây là tài liệu thiết kế, chưa cài đặt executor trong vingo-agent. Không thay đổi API backend.

Có **61 operations**: **59 tool đề xuất bật** và **2 operations không expose cho agent**. Bao gồm POST /checkins dù không có tiền tố /admin. Các API public /events, /showtimes và /me không tính vào danh mục admin.

## 1. Quy ước thiết kế

- Một tool ánh xạ một operation, đặt tên snake_case từ operationId; không có tool HTTP/SQL tùy ý. Các tool nghiệp vụ ghép nhiều bước là workflow của orchestrator.
- Executor chuyển tiếp token phiên của ADMIN; token, base URL và quyền không nằm trong arguments của LLM. Backend vẫn là nơi quyết định phân quyền.
- Arguments gồm path/query đặt ở cấp ngoài và `body` cho JSON payload. Registry có `http.bindings` để executor ánh xạ chính xác. Trường required theo contract; thuộc tính tùy chọn bị bỏ khỏi request khi không cung cấp.
- input_schema dùng JSON Schema 2020-12. Để lấy schema độc lập: ghép `schema_definitions` của registry vào `$defs` của input_schema. Tham chiếu nội bộ dùng #/$defs/..., giữ enum, định dạng, giới hạn, oneOf/allOf và required của contract.
- Registry là định dạng nội bộ, không phải payload gửi nguyên khối cho một nhà cung cấp LLM. Adapter chuyển sang định dạng tool của SDK đang dùng. Không giả định strict=true: schema contract có object mở, nullable và optional cần xử lý theo SDK.
- Nạp tool theo nhóm phù hợp tác vụ; không gửi toàn bộ schema mọi nhóm vào mỗi lượt.
- Các tool ghi có requires_confirmation=true là chính sách thiết kế đề xuất: hiển thị ID, thay đổi trước/sau và hậu quả rồi mới thực thi. Xác nhận được quản lý bởi server theo action lưu sẵn; không dùng boolean confirmed do LLM tự khai.
- Tên tool đã có tiền tố admin được giữ nguyên; các tool catalog không có tiền tố vẫn chỉ đăng ký trong registry ADMIN.

## 2. Các giới hạn cần chốt

- reconcileOrder có contract nhưng AdminOpsController không override: hiện trả mặc định 501, không thể báo là chức năng đã triển khai.
- resolveReview đã có implementation nhưng tài liệu kiến trúc ghi “Agent không có tool đổi trạng thái thanh toán”. Do đó cả resolveReview và reconcileOrder không được bật cho agent trong đề xuất này. Nếu cần mở quyền phải thống nhất lại thiết kế trước.
- CLAUDE.md nói không có hoàn tiền, trong khi contract có action REFUND của resolve-review: contract giải thích đây chỉ là ghi nhận đã hoàn thủ công trên VNPay, không gọi hoàn tiền. Không tự giải quyết mâu thuẫn bằng cách thêm tool refund; giữ operation này ở UI thủ công.
- Có controller override chỉ chứng minh đã có mã ánh xạ, chưa chứng minh chạy thành công trên staging. Không coi API Chat là sẵn sàng chỉ vì có contract; cần kiểm tra riêng trước khi dùng làm cơ chế xác nhận.

## 3. Danh mục đầy đủ

Bản JSON đi kèm là snapshot theo `contract_sha256` ghi trong file. Contract hiện hành cho đăng nhập và phiên nằm trong `contracts/vingo-openapi.yaml`.

Đường dẫn dưới đây đã gồm /api/v1. Chi tiết từng schema, mô tả, response và binding nằm trong admin-agent-tools.json.

| Tool | HTTP và endpoint | Công dụng | Chế độ |
|---|---|---|---|
| `check_in` | `POST /api/v1/checkins` | Quét QR tại cổng | Xác nhận |
| `list_showtime_checkins` | `GET /api/v1/admin/showtimes/{showtime_id}/checkins` | Lịch sử quét theo suất | Đọc |
| `admin_user_statistics` | `GET /api/v1/admin/users/statistics` | Thống kê trạng thái người dùng | Đọc |
| `export_admin_users` | `GET /api/v1/admin/users/export` | Xuất danh sách người dùng | Đọc |
| `admin_list_users` | `GET /api/v1/admin/users` | Tìm và lọc tài khoản | Đọc |
| `admin_get_user` | `GET /api/v1/admin/users/{user_id}` | Xem tài khoản | Đọc |
| `admin_update_user` | `PATCH /api/v1/admin/users/{user_id}` | Đổi quyền hoặc khóa/mở khóa tài khoản | Xác nhận |
| `list_venue_imports` | `GET /api/v1/admin/venue-imports` | Danh sách nháp của admin hiện tại | Đọc |
| `create_venue_import` | `POST /api/v1/admin/venue-imports` | Tạo nháp sân hoặc layout mới | Xác nhận |
| `get_venue_import` | `GET /api/v1/admin/venue-imports/{session_id}` | Mở lại nháp hoặc kết quả đã hoàn tất | Đọc |
| `update_venue_import` | `PATCH /api/v1/admin/venue-imports/{session_id}` | Lưu dữ liệu wizard với revision | Xác nhận |
| `upload_venue_import_files` | `POST /api/v1/admin/venue-imports/{session_id}/files` | Upload và tự phân loại SVG | Xác nhận |
| `delete_venue_import_file` | `DELETE /api/v1/admin/venue-imports/{session_id}/files/{file_id}` | Bỏ file khỏi nháp | Xác nhận |
| `preview_venue_import` | `POST /api/v1/admin/venue-imports/{session_id}/preview` | Ghép nhiều SVG và kiểm tra hình học | Xác nhận |
| `complete_venue_import` | `POST /api/v1/admin/venue-imports/{session_id}/complete` | Lưu nguyên tử sân/nền/layout DRAFT | Xác nhận |
| `cancel_venue_import` | `POST /api/v1/admin/venue-imports/{session_id}/cancel` | Hủy nháp | Xác nhận |
| `list_venue_overviews` | `GET /api/v1/admin/venues/{venue_id}/overviews` | Danh sách phiên bản nền của sân | Đọc |
| `get_venue_overview` | `GET /api/v1/admin/venue-overviews/{overview_id}` | Chi tiết nền và cổng/vùng | Đọc |
| `list_venues` | `GET /api/v1/admin/venues` | Tìm / lọc sân | Đọc |
| `create_venue` | `POST /api/v1/admin/venues` | Tạo sân | Xác nhận |
| `get_admin_venue` | `GET /api/v1/admin/venues/{venue_id}` | Chi tiết sân dành cho quản trị | Đọc |
| `update_venue` | `PATCH /api/v1/admin/venues/{venue_id}` | Sửa sân | Xác nhận |
| `delete_venue` | `DELETE /api/v1/admin/venues/{venue_id}` | Ngừng sử dụng sân (soft-delete) | Xác nhận |
| `get_admin_layout_tree` | `GET /api/v1/admin/venues/{venue_id}/layout-tree` | Cây phiên bản sơ đồ theo loại | Đọc |
| `list_seat_layouts` | `GET /api/v1/admin/venues/{venue_id}/seat-layouts` | Liệt kê phiên bản sơ đồ của sân | Đọc |
| `rename_seat_layout` | `PATCH /api/v1/admin/seat-layouts/{layout_id}` | Đổi tên cấu hình sơ đồ nháp | Xác nhận |
| `get_seat_layout` | `GET /api/v1/admin/seat-layouts/{layout_id}` | Đọc tổng quan sơ đồ sân | Đọc |
| `get_seat_layout_svg` | `GET /api/v1/admin/seat-layouts/{layout_id}/svg` | Đọc SVG toàn sân | Đọc |
| `get_seat_layout_zone` | `GET /api/v1/admin/seat-layouts/{layout_id}/zones/{zone_id}` | Đọc chi tiết khu | Đọc |
| `export_seat_layout_zones` | `GET /api/v1/admin/seat-layouts/{layout_id}/zones.csv` | Tải danh sách khu dưới dạng CSV | Đọc |
| `export_seat_layout_seats` | `GET /api/v1/admin/seat-layouts/{layout_id}/seats.csv` | Tải danh sách ghế dưới dạng CSV | Đọc |
| `publish_seat_layout` | `POST /api/v1/admin/seat-layouts/{layout_id}/publish` | Khoá sơ đồ ghế (PUBLISHED, không sửa được nữa) | Xác nhận |
| `list_admin_events` | `GET /api/v1/admin/events` | Liệt kê sự kiện cho admin | Đọc |
| `create_event` | `POST /api/v1/admin/events` | Tạo sự kiện nháp | Xác nhận |
| `get_admin_event` | `GET /api/v1/admin/events/{event_id}` | Đọc chi tiết sự kiện cho admin | Đọc |
| `update_event` | `PATCH /api/v1/admin/events/{event_id}` | Sửa thông tin chung của sự kiện | Xác nhận |
| `put_price_tiers` | `PUT /api/v1/admin/events/{event_id}/price-tiers` | Đặt toàn bộ hạng giá của sự kiện | Xác nhận |
| `put_zone_prices` | `PUT /api/v1/admin/events/{event_id}/zone-prices` | Gán hạng vé cho từng khu (mỗi khu một hạng) | Xác nhận |
| `publish_event` | `POST /api/v1/admin/events/{event_id}/publish` | DRAFT → PUBLISHED; các suất chuyển SCHEDULED | Xác nhận |
| `cancel_event` | `POST /api/v1/admin/events/{event_id}/cancel` | Huỷ sự kiện (chỉ khi chưa suất nào ON_SALE) | Xác nhận |
| `create_showtime` | `POST /api/v1/admin/events/{event_id}/showtimes` | Tạo suất | Xác nhận |
| `update_showtime` | `PATCH /api/v1/admin/showtimes/{showtime_id}` | Sửa suất (suất DRAFT, hoặc suất SCHEDULED trước sale_opens_at) | Xác nhận |
| `cancel_showtime` | `POST /api/v1/admin/showtimes/{showtime_id}/cancel` | Huỷ suất chưa mở bán | Xác nhận |
| `block_seats` | `POST /api/v1/admin/showtimes/{showtime_id}/seats/block` | block seats | Xác nhận |
| `unblock_seats` | `POST /api/v1/admin/showtimes/{showtime_id}/seats/unblock` | unblock seats | Xác nhận |
| `admin_list_orders` | `GET /api/v1/admin/orders` | Tra cứu đơn | Đọc |
| `admin_get_order` | `GET /api/v1/admin/orders/{order_id}` | Chi tiết đơn, lịch sử trạng thái và các giao dịch | Đọc |
| `resolve_review` | `POST /api/v1/admin/orders/{order_id}/resolve-review` | Xử lý đơn NEEDS_REVIEW | Tắt |
| `reconcile_order` | `POST /api/v1/admin/orders/{order_id}/reconcile` | Gọi querydr ngay để đối soát với VNPay | Tắt |
| `admin_list_tickets` | `GET /api/v1/admin/tickets` | Tra cứu vé | Đọc |
| `void_ticket` | `POST /api/v1/admin/tickets/{ticket_id}/void` | Vô hiệu vé (bắt buộc lý do) | Xác nhận |
| `reissue_ticket_qr` | `POST /api/v1/admin/tickets/{ticket_id}/reissue-qr` | Sinh qr_token mới, QR cũ mất hiệu lực | Xác nhận |
| `admin_resend_ticket` | `POST /api/v1/admin/tickets/{ticket_id}/resend` | Gửi lại vé tới email trên đơn | Xác nhận |
| `list_audit_logs` | `GET /api/v1/admin/audit-logs` | Nhật ký thao tác của admin | Đọc |
| `get_admin_dashboard` | `GET /api/v1/admin/dashboard` | Tổng quan quản trị | Đọc |
| `query_metric` | `GET /api/v1/admin/reports/metrics` | Số liệu bán vé và check-in (dùng cho Admin Copilot) | Đọc |
| `find_nearby_parking` | `GET /api/v1/admin/nearby-parking` | Tìm bãi gửi xe quanh sân | Đọc |
| `suggest_venue_locations` | `GET /api/v1/admin/location-suggestions` | Gợi ý địa điểm bằng Geoapify Address Autocomplete | Đọc |
| `reverse_geocode_venue_location` | `GET /api/v1/admin/location-reverse-geocode` | Chuẩn hóa địa chỉ từ vị trí đã chọn | Đọc |
| `generate_import_parking_guidance` | `POST /api/v1/admin/venue-imports/{import_id}/parking-guidance` | generateImportParkingGuidance | Xác nhận |

## 4. Chi tiết từng tool

Dấu * là trường bắt buộc. Description bên dưới dành cho agent; schema JSON đầy đủ và output theo HTTP status có trong registry.

### 1. `check_in`

- Operation: `checkIn`.
- API: `POST /api/v1/checkins`.
- Controller: `src/main/java/vingo/ticketing/web/CheckinController.java`
- Inputs: `body`* (body)
- Status theo contract: 200, 409, 403, 404.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN (sau này có thể thêm vai trò nhân viên soát vé). Khoá vé theo qr_token; ISSUED và đúng suất → USED. Mọi lần quét đều ghi checkin; qr_token không tồn tại → 404, không ghi checkin.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "body": {
      "type": "object",
      "required": [
        "qr_token",
        "showtime_id"
      ],
      "properties": {
        "qr_token": {
          "type": "string"
        },
        "showtime_id": {
          "type": "string",
          "format": "uuid"
        },
        "gate": {
          "type": "string"
        }
      }
    }
  },
  "required": [
    "body"
  ],
  "additionalProperties": false
}
```

### 2. `list_showtime_checkins`

- Operation: `listShowtimeCheckins`.
- API: `GET /api/v1/admin/showtimes/{showtime_id}/checkins`.
- Controller: `src/main/java/vingo/ticketing/web/CheckinController.java`
- Inputs: `showtime_id`* (path), `cursor` (query), `limit` (query)
- Status theo contract: 200.

Chỉ ADMIN. Đọc dữ liệu. Lịch sử quét theo suất

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [
    "showtime_id"
  ],
  "additionalProperties": false
}
```

### 3. `admin_user_statistics`

- Operation: `adminUserStatistics`.
- API: `GET /api/v1/admin/users/statistics`.
- Controller: `src/main/java/vingo/auth/web/AdminAccountController.java`
- Inputs: Không có.
- Status theo contract: 200, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Đếm trên toàn bộ tài khoản, không phụ thuộc trang danh sách. ACTIVE/LOCKED/UNVERIFIED/DELETED là trạng thái lưu hiện tại; không suy số người online từ ACTIVE.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {},
  "required": [],
  "additionalProperties": false
}
```

### 4. `export_admin_users`

- Operation: `exportAdminUsers`.
- API: `GET /api/v1/admin/users/export`.
- Controller: `src/main/java/vingo/auth/web/AdminAccountController.java`
- Inputs: `q` (query), `role` (query), `status` (query)
- Status theo contract: 200, 400, 401, 403, 413.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. CSV UTF-8 theo bộ lọc, tối đa 10000 tài khoản; vượt giới hạn trả 413 PAYLOAD_TOO_LARGE, cần thu hẹp bộ lọc. Không xuất token, hash, OTP. Các ô được chống CSV formula injection.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "q": {
      "type": "string",
      "maxLength": 200
    },
    "role": {
      "type": "string",
      "enum": [
        "BUYER",
        "ADMIN"
      ]
    },
    "status": {
      "type": "string",
      "enum": [
        "ACTIVE",
        "LOCKED",
        "UNVERIFIED",
        "DELETED"
      ]
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 6. `admin_list_users`

- Operation: `adminListUsers`.
- API: `GET /api/v1/admin/users`.
- Controller: `src/main/java/vingo/auth/web/AdminAccountController.java`
- Inputs: `q` (query), `role` (query), `status` (query), `cursor` (query), `limit` (query), `sort` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Phân trang theo ID giảm dần; không trả mật khẩu, token hoặc Google subject. Lỗi: 400 VALIDATION_ERROR khi cursor sai; 401 UNAUTHORIZED; 403 FORBIDDEN.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "q": {
      "type": "string",
      "maxLength": 200
    },
    "role": {
      "type": "string",
      "enum": [
        "BUYER",
        "ADMIN"
      ]
    },
    "status": {
      "type": "string",
      "enum": [
        "UNVERIFIED",
        "ACTIVE",
        "LOCKED",
        "DELETED"
      ]
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    },
    "sort": {
      "type": "string",
      "enum": [
        "newest",
        "oldest",
        "name"
      ],
      "default": "newest"
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 7. `admin_get_user`

- Operation: `adminGetUser`.
- API: `GET /api/v1/admin/users/{user_id}`.
- Controller: `src/main/java/vingo/auth/web/AdminAccountController.java`
- Inputs: `user_id`* (path)
- Status theo contract: 200, 401, 403, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Trả thông tin tài khoản không có bí mật xác thực. Lỗi: 404 NOT_FOUND khi ID không tồn tại; 401 UNAUTHORIZED; 403 FORBIDDEN.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "user_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "user_id"
  ],
  "additionalProperties": false
}
```

### 8. `admin_update_user`

- Operation: `adminUpdateUser`.
- API: `PATCH /api/v1/admin/users/{user_id}`.
- Controller: `src/main/java/vingo/auth/web/AdminAccountController.java`
- Inputs: `user_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN. Gửi role và/hoặc locked, kèm reason bắt buộc. locked=true là khóa quản trị không tự hết hạn;
locked=false chỉ mở tài khoản đã ACTIVE trước khi bị khóa, không xác minh tài khoản UNVERIFIED.
Đổi quyền chỉ áp dụng tài khoản ACTIVE hoặc LOCKED. Không tự hạ quyền/tự khóa và không làm mất ADMIN ACTIVE cuối cùng.
Tài khoản DELETED không được sửa. Cấp ADMIN yêu cầu tài khoản đã có mật khẩu vì Google login chỉ hỗ trợ BUYER.
Khi có thay đổi, thu hồi mọi phiên và ghi audit trong cùng transaction; người dùng đăng nhập lại.
Gọi lại cùng trạng thái không tạo thêm thay đổi/audit. Không hỗ trợ xóa user hay sửa thông tin cá nhân qua API này.
Lỗi: 400 VALIDATION_ERROR khi body/ràng buộc trạng thái không hợp lệ; 403 FORBIDDEN khi tự hạ quyền/tự khóa;
404 NOT_FOUND; 409 ACTION_STATE_INVALID khi ảnh hưởng ADMIN cuối cùng; 401 UNAUTHORIZED.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "user_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/AdminAccountUpdate"
    }
  },
  "required": [
    "user_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 9. `list_venue_imports`

- Operation: `listVenueImports`.
- API: `GET /api/v1/admin/venue-imports`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `cursor` (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN; chỉ phiên thuộc mình, chưa hết hạn. Cursor UUID giảm dần. Lỗi 400 VALIDATION_ERROR, 401 UNAUTHORIZED, 403 FORBIDDEN.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 10. `create_venue_import`

- Operation: `createVenueImport`.
- API: `POST /api/v1/admin/venue-imports`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `body`* (body)
- Status theo contract: 401, 403, 201, 400, 404.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN. Có target_venue_id để thêm layout vào sân đang hoạt động; có overview_id để dùng nền PUBLISHED cùng sân. Nền được sao chép vào manifest nháp để preview; không được thay file nền khi vẫn dùng overview_id đó. Không tạo venue rỗng. Lỗi 400 VALIDATION_ERROR, 404 NOT_FOUND, 401 UNAUTHORIZED, 403 FORBIDDEN.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "body": {
      "$ref": "#/$defs/VenueImportCreate"
    }
  },
  "required": [
    "body"
  ],
  "additionalProperties": false
}
```

### 11. `get_venue_import`

- Operation: `getVenueImport`.
- API: `GET /api/v1/admin/venue-imports/{session_id}`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path)
- Status theo contract: 400, 401, 403, 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN sở hữu phiên. Hết hạn hoặc khác chủ trả 404 NOT_FOUND; 401 UNAUTHORIZED, 403 FORBIDDEN nếu không có quyền quản trị.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "session_id"
  ],
  "additionalProperties": false
}
```

### 12. `update_venue_import`

- Operation: `updateVenueImport`.
- API: `PATCH /api/v1/admin/venue-imports/{session_id}`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path), `body`* (body)
- Status theo contract: 401, 403, 200, 400, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN sở hữu phiên. draft_data thay thế toàn bộ cấu hình do client quản lý; overview_id chọn khi tạo không đổi. Mỗi thay đổi tăng revision. Lỗi 409 ACTION_STATE_INVALID khi revision cũ/phiên đóng; 400 VALIDATION_ERROR; 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/VenueImportPatch"
    }
  },
  "required": [
    "session_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 13. `upload_venue_import_files`

- Operation: `uploadVenueImportFiles`.
- API: `POST /api/v1/admin/venue-imports/{session_id}/files`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path), `revision`* (multipart-field), `file_refs`* (multipart-files:files)
- Status theo contract: 401, 403, 200, 400, 404, 409, 413, 415.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN sở hữu nháp. Tối đa 10 MiB mỗi request/file nguồn, 32 file và 40 MiB nguồn mỗi phiên;
chọn nhiều file có thể upload nhiều request. Nguồn trùng checksum không ghi trùng.
Backend phân loại OVERVIEW/SEAT_LAYOUT/COMBINED_V1/UNKNOWN từ DOM, không theo tên file.
SVG không hợp lệ vẫn được giữ trong nháp với issues, không preview bytes thô và không cho hoàn tất.
COMBINED_V1 được báo rõ phải xuất tách lớp; không hứa tự tách tùy ý.
Lỗi 413 PAYLOAD_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE, 409 ACTION_STATE_INVALID,
400 VALIDATION_ERROR cho manifest/nhóm file sai; 404 NOT_FOUND.
 Adapter chuyển file_refs thành các phần files (image/svg+xml), revision thành trường multipart. Tối đa 32 file và 10 MiB/request; áp dụng thêm giới hạn phiên theo contract.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    },
    "revision": {
      "type": "integer",
      "minimum": 0
    },
    "file_refs": {
      "type": "array",
      "minItems": 1,
      "maxItems": 32,
      "items": {
        "type": "string"
      },
      "description": "ID tệp SVG đã được người dùng tải lên; executor đọc từ kho tệp tin cậy, không nhận URL tùy ý."
    }
  },
  "required": [
    "session_id",
    "revision",
    "file_refs"
  ],
  "additionalProperties": false
}
```

### 14. `delete_venue_import_file`

- Operation: `deleteVenueImportFile`.
- API: `DELETE /api/v1/admin/venue-imports/{session_id}/files/{file_id}`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path), `file_id`* (path), `revision`* (query)
- Status theo contract: 400, 401, 403, 200, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN sở hữu phiên; file phải thuộc phiên. Lỗi 409 ACTION_STATE_INVALID khi revision cũ/đã đóng; 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    },
    "file_id": {
      "type": "string",
      "format": "uuid"
    },
    "revision": {
      "type": "integer",
      "format": "int64",
      "minimum": 0
    }
  },
  "required": [
    "session_id",
    "file_id",
    "revision"
  ],
  "additionalProperties": false
}
```

### 15. `preview_venue_import`

- Operation: `previewVenueImport`.
- API: `POST /api/v1/admin/venue-imports/{session_id}/preview`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path), `body`* (body)
- Status theo contract: 400, 401, 403, 200, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN sở hữu nháp. Một overview, ít nhất một detail, cùng viewBox; kiểm tra ID trùng, tọa độ, mapping AREA, zone/ghế và cổng. Zone lấy hợp fill của tất cả path/use sau transform, winding và mask; kiểm tra tâm ghế trên vùng hợp được khép khe bằng phép giãn/co bán kính 0.005 đơn vị viewBox. Diện tích ghế ngoài hình zone gốc vẫn phải đạt min(0.025 đơn vị viewBox², 1% diện tích ghế). Không nới collision ghế–cổng hoặc ghế–ghế. Trả valid=false cùng issues để sửa, không tạo venue/layout; revision không đổi. Lỗi 409 ACTION_STATE_INVALID nếu dữ liệu đổi; 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/ImportRevision"
    }
  },
  "required": [
    "session_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 16. `complete_venue_import`

- Operation: `completeVenueImport`.
- API: `POST /api/v1/admin/venue-imports/{session_id}/complete`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path), `body`* (body)
- Status theo contract: 401, 403, 200, 400, 404, 409, 422.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN sở hữu nháp. Kiểm tra lại SVG/mapping, tên/loại layout và thông tin sân. Tạo tất cả dữ liệu và ghi kết quả session trong một transaction. Retry phiên COMPLETED trả cùng IDs, không tạo lại dù revision cũ. Không nhận validation_hash từ client. Lỗi 422 INVALID_SEAT_LAYOUT kèm issues; 400 VALIDATION_ERROR thiếu metadata; 409 ACTION_STATE_INVALID revision cũ/đã hủy; 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/ImportRevision"
    }
  },
  "required": [
    "session_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 17. `cancel_venue_import`

- Operation: `cancelVenueImport`.
- API: `POST /api/v1/admin/venue-imports/{session_id}/cancel`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `session_id`* (path), `body`* (body)
- Status theo contract: 400, 401, 403, 200, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN sở hữu phiên; giữ 7 ngày để cleanup. Retry CANCELLED trả trạng thái cũ. Lỗi 409 ACTION_STATE_INVALID khi revision cũ/đã hoàn tất; 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "session_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/ImportRevision"
    }
  },
  "required": [
    "session_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 18. `list_venue_overviews`

- Operation: `listVenueOverviews`.
- API: `GET /api/v1/admin/venues/{venue_id}/overviews`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `venue_id`* (path), `cursor` (query), `limit` (query)
- Status theo contract: 400, 401, 403, 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN; sân phải hoạt động. Phân trang ID giảm dần; lỗi 400 VALIDATION_ERROR hoặc 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "venue_id": {
      "type": "string",
      "format": "uuid"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [
    "venue_id"
  ],
  "additionalProperties": false
}
```

### 19. `get_venue_overview`

- Operation: `getVenueOverview`.
- API: `GET /api/v1/admin/venue-overviews/{overview_id}`.
- Controller: `src/main/java/vingo/catalog/web/VenueImportController.java`
- Inputs: `overview_id`* (path)
- Status theo contract: 400, 401, 403, 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Trả SVG đã kiểm tra cùng cổng/vùng; lỗi 404 NOT_FOUND nếu sân/nền không tồn tại hoặc sân đã ngừng dùng.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "overview_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "overview_id"
  ],
  "additionalProperties": false
}
```

### 20. `list_venues`

- Operation: `listVenues`.
- API: `GET /api/v1/admin/venues`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `q` (query), `city` (query), `cursor` (query), `limit` (query)
- Status theo contract: 200, 400.

Chỉ ADMIN. Đọc dữ liệu. Tìm / lọc sân

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "q": {
      "type": "string"
    },
    "city": {
      "type": "string"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 21. `create_venue`

- Operation: `createVenue`.
- API: `POST /api/v1/admin/venues`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `body`* (body)
- Status theo contract: 201, 400.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Admin tạo hồ sơ sân với tên, loại, địa chỉ và mã tỉnh hoặc thành phố.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "body": {
      "$ref": "#/$defs/VenueCreateInput"
    }
  },
  "required": [
    "body"
  ],
  "additionalProperties": false
}
```

### 22. `get_admin_venue`

- Operation: `getAdminVenue`.
- API: `GET /api/v1/admin/venues/{venue_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `venue_id`* (path)
- Status theo contract: 200, 401, 403, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Trả location chuẩn, metadata, ngày tạo/cập nhật, updated_by và số layout theo trạng thái. Lỗi 404 NOT_FOUND khi sân đã ngừng dùng hoặc không tồn tại.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "venue_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "venue_id"
  ],
  "additionalProperties": false
}
```

### 23. `update_venue`

- Operation: `updateVenue`.
- API: `PATCH /api/v1/admin/venues/{venue_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `venue_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 404.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Cập nhật các trường được gửi của sân đang hoạt động. Trả 400 VALIDATION_ERROR khi dữ liệu không hợp lệ, 404 NOT_FOUND khi sân không tồn tại hoặc đã ngừng sử dụng.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "venue_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/VenueInput"
    }
  },
  "required": [
    "venue_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 24. `delete_venue`

- Operation: `deleteVenue`.
- API: `DELETE /api/v1/admin/venues/{venue_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `venue_id`* (path)
- Status theo contract: 204, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Ngừng sử dụng sân bằng xóa mềm. Trả 404 NOT_FOUND nếu sân không tồn tại hoặc đã bị xóa; trả 409 VENUE_IN_USE nếu còn suất DRAFT hoặc SCHEDULED chưa kết thúc.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "venue_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "venue_id"
  ],
  "additionalProperties": false
}
```

### 25. `get_admin_layout_tree`

- Operation: `getAdminLayoutTree`.
- API: `GET /api/v1/admin/venues/{venue_id}/layout-tree`.
- Controller: `src/main/java/vingo/catalog/web/AdminWorkspaceController.java`
- Inputs: `venue_id`* (path), `layout_type`* (query), `cursor` (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Chọn FOOTBALL hoặc CONCERT để lấy đúng loại layout của sân, theo phiên bản giảm dần. Cây không phụ thuộc file nhập tạm; tải ghế qua API chi tiết zone hiện có. Không cho sửa hình học. Lỗi 400 VALIDATION_ERROR hoặc 404 NOT_FOUND.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "venue_id": {
      "type": "string",
      "format": "uuid"
    },
    "layout_type": {
      "$ref": "#/$defs/LayoutType"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [
    "venue_id",
    "layout_type"
  ],
  "additionalProperties": false
}
```

### 26. `list_seat_layouts`

- Operation: `listSeatLayouts`.
- API: `GET /api/v1/admin/venues/{venue_id}/seat-layouts`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `venue_id`* (path), `layout_type` (query), `status` (query), `include_legacy` (query), `cursor` (query), `limit` (query)
- Status theo contract: 200, 400, 404.

Chỉ ADMIN. Đọc dữ liệu. Trả tên và phiên bản sơ đồ của sân, mới nhất trước. Chọn bằng id cụ thể khi tạo suất, không tự lấy bản mới nhất.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "venue_id": {
      "type": "string",
      "format": "uuid"
    },
    "layout_type": {
      "$ref": "#/$defs/LayoutType"
    },
    "status": {
      "type": "string",
      "enum": [
        "DRAFT",
        "PUBLISHED"
      ]
    },
    "include_legacy": {
      "type": "boolean",
      "default": false
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [
    "venue_id"
  ],
  "additionalProperties": false
}
```

### 27. `rename_seat_layout`

- Operation: `renameSeatLayout`.
- API: `PATCH /api/v1/admin/seat-layouts/{layout_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ đổi tên phiên bản DRAFT thuộc sân đang hoạt động. Không đổi khu, ghế, số phiên bản hoặc liên kết của suất. Bản PUBLISHED bị khóa tên.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/SeatLayoutNameInput"
    }
  },
  "required": [
    "layout_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 28. `get_seat_layout`

- Operation: `getSeatLayout`.
- API: `GET /api/v1/admin/seat-layouts/{layout_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path)
- Status theo contract: 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Trả tên cấu hình, thông tin phiên bản, hệ tọa độ và danh sách khu để dựng bản đồ và mini map.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "layout_id"
  ],
  "additionalProperties": false
}
```

### 29. `get_seat_layout_svg`

- Operation: `getSeatLayoutSvg`.
- API: `GET /api/v1/admin/seat-layouts/{layout_id}/svg`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path)
- Status theo contract: 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Trả SVG đã chuẩn hóa của phiên bản. Layout không có SVG trả 404.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "layout_id"
  ],
  "additionalProperties": false
}
```

### 30. `get_seat_layout_zone`

- Operation: `getSeatLayoutZone`.
- API: `GET /api/v1/admin/seat-layouts/{layout_id}/zones/{zone_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path), `zone_id`* (path)
- Status theo contract: 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Trả hàng ghế hoặc sức chứa. Khu phải thuộc đúng phiên bản trong URL.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    },
    "zone_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "layout_id",
    "zone_id"
  ],
  "additionalProperties": false
}
```

### 31. `export_seat_layout_zones`

- Operation: `exportSeatLayoutZones`.
- API: `GET /api/v1/admin/seat-layouts/{layout_id}/zones.csv`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path)
- Status theo contract: 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Xuất CSV UTF-8 với các cột zone_code, label, stand_code, level, kind, capacity, svg_id.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "layout_id"
  ],
  "additionalProperties": false
}
```

### 32. `export_seat_layout_seats`

- Operation: `exportSeatLayoutSeats`.
- API: `GET /api/v1/admin/seat-layouts/{layout_id}/seats.csv`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path)
- Status theo contract: 200, 404.

Chỉ ADMIN. Đọc dữ liệu. Xuất CSV UTF-8 với các cột zone_code, row_label, seat_number, x, y. Chỉ khu SEATED có dòng ghế.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "layout_id"
  ],
  "additionalProperties": false
}
```

### 33. `publish_seat_layout`

- Operation: `publishSeatLayout`.
- API: `POST /api/v1/admin/seat-layouts/{layout_id}/publish`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `layout_id`* (path)
- Status theo contract: 200, 404.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chuyển phiên bản sơ đồ từ DRAFT sang PUBLISHED và ghi nhận thời điểm công bố. Gọi lại trên phiên bản đã PUBLISHED sẽ trả về cùng phiên bản và giữ nguyên published_at.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "layout_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "layout_id"
  ],
  "additionalProperties": false
}
```

### 34. `list_admin_events`

- Operation: `listAdminEvents`.
- API: `GET /api/v1/admin/events`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `q` (query), `status` (query), `category` (query), `cursor` (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Admin liệt kê sự kiện ở mọi trạng thái, gồm DRAFT. Có thể lọc theo tên, trạng thái và loại sự kiện. q tìm chuỗi con trong title, không phân biệt hoa thường. Sắp xếp created_at giảm dần, sau đó id giảm dần. Dùng next_cursor để đọc trang tiếp theo với cùng bộ lọc. Không thay đổi dữ liệu.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "q": {
      "type": "string"
    },
    "status": {
      "$ref": "#/$defs/EventStatus"
    },
    "category": {
      "$ref": "#/$defs/EventCategory"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 35. `create_event`

- Operation: `createEvent`.
- API: `POST /api/v1/admin/events`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `body`* (body)
- Status theo contract: 201, 400, 401, 403, 422.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Admin tạo sự kiện ở trạng thái DRAFT. Bắt buộc title không trắng và category hợp lệ. Nếu không gửi max_tickets_per_order thì mặc định 10. Hai poster được lưu độc lập và phải là URL tuyệt đối dùng HTTP hoặc HTTPS khi được cung cấp. Thao tác này chưa tạo suất, giá hoặc tồn kho.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "body": {
      "$ref": "#/$defs/EventCreateInput"
    }
  },
  "required": [
    "body"
  ],
  "additionalProperties": false
}
```

### 36. `get_admin_event`

- Operation: `getAdminEvent`.
- API: `GET /api/v1/admin/events/{event_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path)
- Status theo contract: 200, 401, 403, 404.

Chỉ ADMIN. Đọc dữ liệu. Admin đọc thông tin sự kiện để xem và tiếp tục chỉnh sửa. Trả cả sự kiện DRAFT, PUBLISHED, ENDED và CANCELLED. Bao gồm thông tin chung, hai poster, hạng giá và các suất, kể cả suất DRAFT. Không thay đổi trạng thái hoặc dữ liệu sự kiện.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "event_id"
  ],
  "additionalProperties": false
}
```

### 37. `update_event`

- Operation: `updateEvent`.
- API: `PATCH /api/v1/admin/events/{event_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409, 422.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Admin sửa thông tin chung của sự kiện DRAFT hoặc PUBLISHED. Chỉ cập nhật trường có giá trị khác null; trường không gửi hoặc null giữ nguyên. attributes khác null thay thế toàn bộ object thuộc tính. Body rỗng là thao tác không thay đổi dữ liệu. Chỉ sự kiện DRAFT được đổi sân hoặc layout. Gửi cả venue_id và seat_layout_id. Layout và sơ đồ tổng quát của layout phải đã công bố, thuộc sân đang hoạt động được chọn, và layout phải cùng loại với sự kiện sau cập nhật. Hủy các suất còn dùng layout cũ trước khi đổi. Thiếu một trong hai ID hoặc còn suất dùng layout cũ trả 400 VALIDATION_ERROR. Layout không đáp ứng điều kiện trả 422 INVALID_SEAT_LAYOUT. Sự kiện ENDED hoặc CANCELLED, hoặc yêu cầu đổi sân/layout hay đổi loại của sự kiện đã chọn layout sau công bố, trả 409 EVENT_LOCKED. Sau khi cập nhật thành công, Search làm mới cache sau commit.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/EventPatchInput"
    }
  },
  "required": [
    "event_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 38. `put_price_tiers`

- Operation: `putPriceTiers`.
- API: `PUT /api/v1/admin/events/{event_id}/price-tiers`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Admin đặt toàn bộ danh sách hạng giá. Event phải là DRAFT hoặc PUBLISHED, không có đơn và không có suất đã công bố (SCHEDULED) đã tới giờ mở bán. Suất DRAFT chưa bán được nên không khóa, kể cả khi đã qua sale_opens_at. Nếu có id, hạng phải thuộc đúng sự kiện. Nếu không có id, dùng lại hạng cùng tên đã bỏ khoảng trắng, hoặc tạo mới nếu chưa có. Không chấp nhận trùng id hoặc tên. Hạng bị bỏ khỏi danh sách chỉ được xóa nếu không còn được gán khu hoặc tham chiếu bởi dòng đơn hàng. Mọi thay đổi trong cùng transaction; lỗi thì rollback toàn bộ. Search làm mới cache sau commit.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/PriceTierInput"
      }
    }
  },
  "required": [
    "event_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 39. `put_zone_prices`

- Operation: `putZonePrices`.
- API: `PUT /api/v1/admin/events/{event_id}/zone-prices`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Admin thay toàn bộ danh sách gán hạng giá cho khu. Áp dụng cùng quy tắc khóa như PUT price-tiers: event DRAFT hoặc PUBLISHED, chưa có đơn và không có suất đã công bố (SCHEDULED) đã tới giờ mở bán. Mỗi zone_id chỉ xuất hiện một lần. Khu phải thuộc layout đã chọn của sự kiện. Sự kiện cũ chưa có layout chung dùng các layout của những suất chưa hủy. Hạng giá phải thuộc đúng sự kiện. Cho phép cấu hình chưa đủ khu trước bước công bố. Toàn bộ thay đổi trong cùng transaction; lỗi thì rollback. Search làm mới cache sau commit.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/ZonePriceInput"
      }
    }
  },
  "required": [
    "event_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 40. `publish_event`

- Operation: `publishEvent`.
- API: `POST /api/v1/admin/events/{event_id}/publish`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path)
- Status theo contract: 200, 401, 403, 404, 409, 422.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Công bố nguyên tử sự kiện DRAFT khi có ít nhất một suất chưa hủy, layout đã công bố, sân còn hoạt động, thời gian suất hợp lệ (chưa qua giờ đóng bán) và mọi khu có hạng giá thuộc sự kiện. Thiếu cấu hình trả 422 EVENT_INCOMPLETE. Suất DRAFT chuyển SCHEDULED; giữ nguyên tồn kho hiện có, kể cả BLOCKED, chỉ bổ sung dòng thiếu. Gửi lại sự kiện PUBLISHED trả dữ liệu hiện tại. ENDED/CANCELLED trả EVENT_LOCKED. Cache làm mới sau commit. Hệ thống tính ON_SALE khi tới giờ, không lưu trạng thái đó.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "event_id"
  ],
  "additionalProperties": false
}
```

### 41. `cancel_event`

- Operation: `cancelEvent`.
- API: `POST /api/v1/admin/events/{event_id}/cancel`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path), `body` (body)
- Status theo contract: 200, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Huỷ sự kiện (chỉ khi chưa suất nào ON_SALE)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "reason"
      ],
      "properties": {
        "reason": {
          "type": "string",
          "minLength": 3
        }
      }
    }
  },
  "required": [
    "event_id"
  ],
  "additionalProperties": false
}
```

### 42. `create_showtime`

- Operation: `createShowtime`.
- API: `POST /api/v1/admin/events/{event_id}/showtimes`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `event_id`* (path), `body`* (body)
- Status theo contract: 201, 400, 404, 409, 422.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Tạo suất diễn gắn với seat_layout_id đã PUBLISHED thuộc đúng sân đó. Mỗi sự kiện Bóng đá có tối đa một suất chưa hủy; tạo suất thứ hai trả VALIDATION_ERROR. Mọi suất của sự kiện đã chọn layout phải dùng cùng layout đó. Sự kiện cũ chưa chọn layout vẫn có thể dùng địa điểm riêng cho từng suất. Sự kiện phải là DRAFT hoặc PUBLISHED; trạng thái ENDED/CANCELLED trả EVENT_LOCKED. Tạo tồn kho của phiên bản đã chọn trong cùng transaction và làm mới cache sau commit.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/ShowtimeInput"
    }
  },
  "required": [
    "event_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 43. `update_showtime`

- Operation: `updateShowtime`.
- API: `PATCH /api/v1/admin/showtimes/{showtime_id}`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `showtime_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 404, 409, 422.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Cập nhật thông tin suất diễn trước thời điểm mở bán hiện tại, không áp default cho trường không gửi hoặc null. Sự kiện ENDED/CANCELLED trả EVENT_LOCKED; suất đã hủy hoặc đã tới giờ mở bán trả SHOWTIME_LOCKED. Nếu đổi seat_layout_id thì sơ đồ phải đã PUBLISHED và thuộc đúng sân. Đổi sơ đồ chỉ được khi chưa có đơn hoặc tồn kho HELD/SOLD/BLOCKED. Metadata, tồn kho và sự kiện làm mới cache được xử lý trong cùng transaction.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/ShowtimeInput"
    }
  },
  "required": [
    "showtime_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 44. `cancel_showtime`

- Operation: `cancelShowtime`.
- API: `POST /api/v1/admin/showtimes/{showtime_id}/cancel`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `showtime_id`* (path), `body` (body)
- Status theo contract: 200, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Huỷ suất chưa mở bán

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "reason"
      ],
      "properties": {
        "reason": {
          "type": "string",
          "minLength": 3
        }
      }
    }
  },
  "required": [
    "showtime_id"
  ],
  "additionalProperties": false
}
```

### 45. `block_seats`

- Operation: `blockSeats`.
- API: `POST /api/v1/admin/showtimes/{showtime_id}/seats/block`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `showtime_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. ADMIN thay đổi ghế theo suất, nguyên tử toàn bộ danh sách; hỗ trợ suất DRAFT. AVAILABLE hoặc HELD có held_until < giờ hiện tại được khóa; HELD còn hạn và SOLD trả SEAT_TAKEN. Mở khóa chỉ chấp nhận BLOCKED hoặc AVAILABLE. Gửi lại trạng thái đích thành công, không ghi thêm lịch sử. Không tự thêm ghế cặp. Ghế ngoài sơ đồ, danh sách rỗng/trùng/null trả VALIDATION_ERROR. Event ENDED/CANCELLED trả EVENT_LOCKED; suất CANCELLED hoặc đã kết thúc trả SHOWTIME_LOCKED. Lý do khóa được lưu; dữ liệu và sự kiện làm mới cache cùng transaction.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "seat_ids",
        "reason"
      ],
      "properties": {
        "seat_ids": {
          "type": "array",
          "minItems": 1,
          "maxItems": 200,
          "items": {
            "type": "string",
            "format": "uuid"
          }
        },
        "reason": {
          "type": "string",
          "minLength": 1,
          "maxLength": 1000,
          "pattern": "[\\s\\S]*\\S[\\s\\S]*"
        }
      }
    }
  },
  "required": [
    "showtime_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 46. `unblock_seats`

- Operation: `unblockSeats`.
- API: `POST /api/v1/admin/showtimes/{showtime_id}/seats/unblock`.
- Controller: `src/main/java/vingo/catalog/web/AdminVenueController.java`
- Inputs: `showtime_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. ADMIN thay đổi ghế theo suất, nguyên tử toàn bộ danh sách; hỗ trợ suất DRAFT. AVAILABLE hoặc HELD có held_until < giờ hiện tại được khóa; HELD còn hạn và SOLD trả SEAT_TAKEN. Mở khóa chỉ chấp nhận BLOCKED hoặc AVAILABLE. Gửi lại trạng thái đích thành công, không ghi thêm lịch sử. Không tự thêm ghế cặp. Ghế ngoài sơ đồ, danh sách rỗng/trùng/null trả VALIDATION_ERROR. Event ENDED/CANCELLED trả EVENT_LOCKED; suất CANCELLED hoặc đã kết thúc trả SHOWTIME_LOCKED. Lý do khóa được lưu; dữ liệu và sự kiện làm mới cache cùng transaction.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "seat_ids"
      ],
      "properties": {
        "seat_ids": {
          "type": "array",
          "minItems": 1,
          "maxItems": 200,
          "items": {
            "type": "string",
            "format": "uuid"
          }
        }
      }
    }
  },
  "required": [
    "showtime_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 47. `admin_list_orders`

- Operation: `adminListOrders`.
- API: `GET /api/v1/admin/orders`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `status` (query), `event_id` (query), `showtime_id` (query), `q` (query), `cursor` (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN; đọc đơn của mọi buyer, không thay đổi dữ liệu. Các bộ lọc kết hợp bằng AND;
event_id lọc theo showtime.event_id. Sắp xếp created_at DESC, id DESC;
phân trang keyset như GET /orders, next_cursor null ở trang cuối, limit từ 1 đến 100.
q sau trim phải dài 3–254 ký tự và khớp CHÍNH XÁC một trong: mã đơn
(upper(code) = upper(q)), email (lower(buyer_email) = lower(q)),
hoặc SĐT (buyer_phone = q, giữ nguyên định dạng). Không dùng LIKE; % và _ là ký tự thường.
Không log q. q sai độ dài, cursor hỏng hoặc limit ngoài khoảng → 400 VALIDATION_ERROR.
Ví dụ lỗi q: {"status":400,"code":"VALIDATION_ERROR","detail":"q sau trim phải dài 3–254 ký tự"}.
Ví dụ cursor hỏng: {"status":400,"code":"VALIDATION_ERROR","detail":"Cursor không hợp lệ"}.
Ví dụ limit sai: {"status":400,"code":"VALIDATION_ERROR","detail":"limit phải từ 1 đến 100"}.
Thiếu token: {"status":401,"code":"UNAUTHORIZED"}; BUYER: {"status":403,"code":"FORBIDDEN"}.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "status": {
      "$ref": "#/$defs/OrderStatus"
    },
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "q": {
      "type": "string",
      "description": "Khớp chính xác mã đơn/email không phân biệt hoa thường hoặc SĐT nguyên văn; trim rồi kiểm tra 3–254 ký tự"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 48. `admin_get_order`

- Operation: `adminGetOrder`.
- API: `GET /api/v1/admin/orders/{order_id}`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `order_id`* (path)
- Status theo contract: 200, 401, 403, 404.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN; đọc đơn của mọi buyer gồm items, buyer, payments và history, không thay đổi dữ liệu.
payments sắp xếp created_at ASC, id ASC; last_response_code lấy từ payment_transaction
mới nhất theo received_at của từng intent. Không có payment thì payments là mảng rỗng.
Không trả payment_url của PaymentIntent (không có trường hoặc null), không lộ URL thanh toán.
history sắp xếp at ASC, id ASC; actor là email của order_status_history.actor_id
(JOIN users), hoặc "SYSTEM" khi actor_id NULL.
Thiếu token: {"status":401,"code":"UNAUTHORIZED"}; BUYER: {"status":403,"code":"FORBIDDEN"}.
Đơn không tồn tại: {"status":404,"code":"NOT_FOUND","detail":"Không tìm thấy đơn"}.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "order_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "order_id"
  ],
  "additionalProperties": false
}
```

### 49. `resolve_review`

- Operation: `resolveReview`.
- API: `POST /api/v1/admin/orders/{order_id}/resolve-review`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `order_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN; bắt buộc Idempotency-Key và reason. Khoá đơn FOR UPDATE, chỉ xử lý NEEDS_REVIEW.
Cùng key và body trả kết quả cũ; cùng key khác body → IDEMPOTENCY_CONFLICT.
ISSUE_TICKETS phải lấy lại toàn bộ tồn kho cũ theo order_item, không đổi ghế:
ghế AVAILABLE hoặc HELD quá hạn (held_until < now) → SOLD với order_id của đơn;
khu đứng tăng sold theo quantity khi capacity - sold - held đủ. Thiếu ghế → SEAT_TAKEN
(không kèm gợi ý ghế), thiếu khu đứng → NOT_ENOUGH_STOCK; rollback toàn bộ thao tác.
Suất đã diễn (ends_at <= now) hoặc CANCELLED → ORDER_STATE_INVALID; đã đóng bán
nhưng chưa diễn vẫn được xử lý. Thành công chuyển đơn PAID, paid_at = now, ghi history
NEEDS_REVIEW → PAID với actor_id admin và reason, phát vé cùng ticket.issued,
order.paid và email vé qua outbox nếu có buyer_email; thiếu email vẫn thành công.
REFUND chỉ đánh dấu REFUNDED sau khi admin hoàn thủ công trên VNPay, ghi history;
không gọi API hoàn tiền và không giải phóng tồn kho vì đơn không còn giữ.
Mọi thay đổi, history, audit_log trước/sau, outbox và realtime nằm trong cùng transaction.


**Không đăng ký:** Kiến trúc không cho agent đổi trạng thái thanh toán; dành cho UI quản trị.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "order_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "action",
        "reason"
      ],
      "properties": {
        "action": {
          "type": "string",
          "enum": [
            "ISSUE_TICKETS",
            "REFUND"
          ],
          "description": "REFUND chỉ đánh dấu đơn REFUNDED sau khi admin đã hoàn tiền thủ công trên cổng VNPay. Hệ thống không gọi API hoàn tiền."
        },
        "reason": {
          "type": "string"
        }
      }
    }
  },
  "required": [
    "order_id",
    "body"
  ],
  "additionalProperties": false
}
```

### 50. `reconcile_order`

- Operation: `reconcileOrder`.
- API: `POST /api/v1/admin/orders/{order_id}/reconcile`.
- Controller: Không có override; kiểm tra default interface.
- Inputs: `order_id`* (path)
- Status theo contract: 200.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Gọi querydr ngay để đối soát với VNPay

**Không đăng ký:** Controller chưa override, default 501; đồng thời ngoài phạm vi thanh toán của agent.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "order_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "order_id"
  ],
  "additionalProperties": false
}
```

### 51. `admin_list_tickets`

- Operation: `adminListTickets`.
- API: `GET /api/v1/admin/tickets`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `code` (query), `order_id` (query), `showtime_id` (query), `status` (query), `cursor` (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN; đọc vé của mọi buyer, không thay đổi dữ liệu. Các bộ lọc kết hợp bằng AND;
code khớp chính xác, không phân biệt hoa thường, không dùng LIKE.
Sắp xếp issued_at DESC, id DESC; phân trang keyset như GET /tickets,
next_cursor null ở trang cuối, limit từ 1 đến 100.
Admin KHÔNG BAO GIỜ nhận qr_token: mọi vé đều không có trường hoặc null,
kể cả vé ISSUED và kể cả admin là chủ vé.
Ví dụ cursor hỏng: {"status":400,"code":"VALIDATION_ERROR","detail":"Cursor không hợp lệ"}.
Ví dụ limit sai: {"status":400,"code":"VALIDATION_ERROR","detail":"limit phải từ 1 đến 100"}.
Thiếu token: {"status":401,"code":"UNAUTHORIZED"}; BUYER: {"status":403,"code":"FORBIDDEN"}.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "code": {
      "type": "string"
    },
    "order_id": {
      "type": "string",
      "format": "uuid"
    },
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "status": {
      "$ref": "#/$defs/TicketStatus"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 52. `void_ticket`

- Operation: `voidTicket`.
- API: `POST /api/v1/admin/tickets/{ticket_id}/void`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `ticket_id`* (path), `body` (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN; reason ít nhất 3 ký tự. Khoá vé FOR UPDATE trước khi kiểm trạng thái.
Chỉ ISSUED → VOID, đặt voided_at, không trả qr_token; ghế vẫn SOLD, không mở bán lại.
USED → TICKET_USED, VOID → TICKET_NOT_VALID. Ghi audit VOID_TICKET cùng transaction,
gồm admin, thời điểm, reason và status trước/sau; QR cũ không còn dùng để vào cổng.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "ticket_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "reason"
      ],
      "properties": {
        "reason": {
          "type": "string",
          "minLength": 3
        }
      }
    }
  },
  "required": [
    "ticket_id"
  ],
  "additionalProperties": false
}
```

### 53. `reissue_ticket_qr`

- Operation: `reissueTicketQr`.
- API: `POST /api/v1/admin/tickets/{ticket_id}/reissue-qr`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `ticket_id`* (path), `body` (body)
- Status theo contract: 200, 400, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN; reason ít nhất 3 ký tự. Khoá vé FOR UPDATE; chỉ vé ISSUED được cấp lại QR.
Chỉ đổi qr_token bằng 32 byte SecureRandom mã hoá base64url; Ticket trả về KHÔNG có qr_token
(admin không nhận QR vào cổng), khách nhận QR mới qua gửi lại vé. QR cũ không còn tìm thấy. USED → TICKET_USED, VOID → TICKET_NOT_VALID.
Ghi audit REISSUE_QR cùng transaction với status ISSUED trước/sau, không lưu qr_token.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "ticket_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "type": "object",
      "required": [
        "reason"
      ],
      "properties": {
        "reason": {
          "type": "string",
          "minLength": 3
        }
      }
    }
  },
  "required": [
    "ticket_id"
  ],
  "additionalProperties": false
}
```

### 54. `admin_resend_ticket`

- Operation: `adminResendTicket`.
- API: `POST /api/v1/admin/tickets/{ticket_id}/resend`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `ticket_id`* (path)
- Status theo contract: 202, 401, 403, 404, 409.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Chỉ ADMIN, không rate limit. Khoá vé FOR UPDATE; chỉ vé ISSUED được gửi.
USED → TICKET_USED, VOID → TICKET_NOT_VALID; không có buyer_email → ORDER_STATE_INVALID.
Ghi email qua outbox và audit RESEND_TICKET trong cùng transaction:
before null, after chứa outbox_id, không lưu email trong audit.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "ticket_id": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "ticket_id"
  ],
  "additionalProperties": false
}
```

### 55. `list_audit_logs`

- Operation: `listAuditLogs`.
- API: `GET /api/v1/admin/audit-logs`.
- Controller: `src/main/java/vingo/adminops/web/AdminOpsController.java`
- Inputs: `entity` (query), `entity_id` (query), `cursor` (query), `limit` (query), `actor_id` (query), `action` (query), `from` (query), `to` (query), `outcome` (query), `activity_only` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN; lọc entity và entity_id, mới nhất trước theo at DESC rồi id DESC. Phân trang keyset cursor, limit từ 1 đến 100; limit hoặc cursor sai trả 400.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "entity": {
      "type": "string"
    },
    "entity_id": {
      "type": "string",
      "format": "uuid"
    },
    "cursor": {
      "type": "string"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20
    },
    "actor_id": {
      "type": "string",
      "format": "uuid"
    },
    "action": {
      "type": "string",
      "maxLength": 100
    },
    "from": {
      "type": "string",
      "format": "date-time"
    },
    "to": {
      "type": "string",
      "format": "date-time"
    },
    "outcome": {
      "type": "string",
      "enum": [
        "STARTED",
        "SUCCEEDED",
        "FAILED"
      ]
    },
    "activity_only": {
      "type": "boolean",
      "default": false,
      "description": "Chỉ hoạt động ghi thành công cho feed; loại bỏ request đọc và audit nghiệp vụ trùng"
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 56. `get_admin_dashboard`

- Operation: `getAdminDashboard`.
- API: `GET /api/v1/admin/dashboard`.
- Controller: `src/main/java/vingo/reporting/web/MetricsController.java`
- Inputs: `from` (query), `to` (query), `group_by` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Chỉ ADMIN. Khoảng [from,to), mặc định 30 ngày, tối đa 366 ngày. Chia ngày/tháng theo Asia/Ho_Chi_Minh. Doanh thu và vé bán theo paid_at của đơn PAID; không tự giảm theo vé VOID. Tỷ lệ bán dùng SOLD/capacity của các suất SCHEDULED có starts_at trong kỳ. Suất sắp diễn và số đang mở bán là snapshot tại computed_at, độc lập khoảng lọc. Kỳ trước có cùng độ dài. Không trả dữ liệu log máy chủ. Lỗi 400 VALIDATION_ERROR.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "from": {
      "type": "string",
      "format": "date-time"
    },
    "to": {
      "type": "string",
      "format": "date-time"
    },
    "group_by": {
      "type": "string",
      "enum": [
        "day",
        "month"
      ],
      "default": "day"
    }
  },
  "required": [],
  "additionalProperties": false
}
```

### 57. `query_metric`

- Operation: `queryMetric`.
- API: `GET /api/v1/admin/reports/metrics`.
- Controller: `src/main/java/vingo/reporting/web/MetricsController.java`
- Inputs: `metric`* (query), `event_id` (query), `showtime_id` (query), `price_tier_id` (query), `from` (query), `to` (query), `group_by` (query)
- Status theo contract: 200, 400, 401, 403.

Chỉ ADMIN. Đọc dữ liệu. Tính trên dữ liệu hiện tại; lọc thời gian theo [from,to), mỗi mốc có thể bỏ qua.
Nhóm day/hour theo UTC; tier/zone theo UUID hạng vé/khu vực.
tickets_sold là tổng quantity của đơn PAID, theo paid_at.
revenue là tổng quantity * unit_price của đơn PAID, theo paid_at, đơn vị VND.
checkins đếm các vé USED khác nhau của đơn PAID, theo lần quét OK đầu tiên.
fill_rate = 100 * tổng sold / tổng capacity, theo starts_at; mẫu bằng 0 trả 0.
group_by=none trả value; các nhóm khác trả series, nhóm không có dữ liệu trả [].
Ví dụ 400 khi from không trước to:
{"status":400,"code":"VALIDATION_ERROR","detail":"from must be earlier than to"}
Ví dụ 401 khi thiếu token: {"status":401,"code":"UNAUTHORIZED"}.
Ví dụ 403 khi người dùng không có vai trò ADMIN: {"status":403,"code":"FORBIDDEN"}.


```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "metric": {
      "type": "string",
      "enum": [
        "tickets_sold",
        "revenue",
        "fill_rate",
        "checkins"
      ]
    },
    "event_id": {
      "type": "string",
      "format": "uuid"
    },
    "showtime_id": {
      "type": "string",
      "format": "uuid"
    },
    "price_tier_id": {
      "type": "string",
      "format": "uuid"
    },
    "from": {
      "type": "string",
      "format": "date-time"
    },
    "to": {
      "type": "string",
      "format": "date-time"
    },
    "group_by": {
      "type": "string",
      "enum": [
        "none",
        "day",
        "hour",
        "month",
        "tier",
        "zone"
      ],
      "default": "none"
    }
  },
  "required": [
    "metric"
  ],
  "additionalProperties": false
}
```

### 58. `find_nearby_parking`

- Operation: `findNearbyParking`.
- API: `GET /api/v1/admin/nearby-parking`.
- Controller: `src/main/java/vingo/catalog/web/VenueSetupController.java`
- Inputs: `lat`* (query), `lng`* (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403, 429, 503.

Chỉ ADMIN. Đọc dữ liệu. Tìm bằng Geoapify Places trong 3 km đường chim bay, tối đa 10 gợi ý xếp theo khoảng cách.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "lat": {
      "type": "number",
      "minimum": -90,
      "maximum": 90
    },
    "lng": {
      "type": "number",
      "minimum": -180,
      "maximum": 180
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 10,
      "default": 5
    }
  },
  "required": [
    "lat",
    "lng"
  ],
  "additionalProperties": false
}
```

### 59. `suggest_venue_locations`

- Operation: `suggestVenueLocations`.
- API: `GET /api/v1/admin/location-suggestions`.
- Controller: `src/main/java/vingo/catalog/web/VenueSetupController.java`
- Inputs: `q`* (query), `limit` (query)
- Status theo contract: 200, 400, 401, 403, 429, 503.

Chỉ ADMIN. Đọc dữ liệu. Proxy server tới Geoapify; chỉ nhận q từ 3 ký tự, debounce 300–500 ms ở client, giới hạn 10 kết quả. API key không gửi về client. Dữ liệu OSM được trả theo điều khoản attribution của Geoapify.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "q": {
      "type": "string",
      "minLength": 3,
      "maxLength": 200
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 10,
      "default": 5
    }
  },
  "required": [
    "q"
  ],
  "additionalProperties": false
}
```

### 60. `reverse_geocode_venue_location`

- Operation: `reverseGeocodeVenueLocation`.
- API: `GET /api/v1/admin/location-reverse-geocode`.
- Controller: `src/main/java/vingo/catalog/web/VenueSetupController.java`
- Inputs: `lat`* (query), `lng`* (query)
- Status theo contract: 200, 400, 401, 403, 404, 429, 503.

Chỉ ADMIN. Đọc dữ liệu. Proxy reverse geocode Geoapify sau khi admin chọn điểm hoặc thả marker; không gọi liên tục trong lúc kéo marker.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "lat": {
      "type": "number",
      "minimum": -90,
      "maximum": 90
    },
    "lng": {
      "type": "number",
      "minimum": -180,
      "maximum": 180
    }
  },
  "required": [
    "lat",
    "lng"
  ],
  "additionalProperties": false
}
```

### 61. `generate_import_parking_guidance`

- Operation: `generateImportParkingGuidance`.
- API: `POST /api/v1/admin/venue-imports/{import_id}/parking-guidance`.
- Controller: `src/main/java/vingo/catalog/web/VenueSetupController.java`
- Inputs: `import_id`* (path), `body`* (body)
- Status theo contract: 200, 400, 401, 403, 404, 409, 413, 415, 503.

Chỉ ADMIN. Thay đổi dữ liệu; chỉ thực thi sau khi admin xác nhận nội dung cụ thể. Sinh nháp bằng dữ kiện phiên nhập thuộc admin hiện tại, kiểm revision trước và sau HTTP; không tự công khai. Lỗi revision trả 409 ACTION_STATE_INVALID.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "import_id": {
      "type": "string",
      "format": "uuid"
    },
    "body": {
      "$ref": "#/$defs/ImportParkingGuidanceRequest"
    }
  },
  "required": [
    "import_id",
    "body"
  ],
  "additionalProperties": false
}
```

## 5. Executor và kết quả trả về

Luồng thực thi đề xuất: xác thực phiên ADMIN → chọn tool từ allowlist → validate arguments bằng input_schema đã ghép $defs → kiểm quyền và xác nhận nếu ghi → ánh xạ HTTP → gọi backend → chuẩn hóa response. Không suy diễn method/path từ nội dung chat.

Envelope phía agent đề xuất (không thay đổi response backend):

```json
{"ok":true,"tool":"admin_list_orders","http_status":200,"data":{"data":[],"next_cursor":null},"error":null}
```

- Giữ tiền nguyên VND, thời gian ISO 8601 có offset; chuyển yêu cầu “hôm nay/tháng này” từ múi giờ nghiệp vụ sang UTC trước khi gọi.
- Giữ nguyên next_cursor; chỉ gọi trang tiếp theo khi cần. Không kết luận không có dữ liệu dựa trên một trang chưa hết.
- JSON: trả dữ liệu nghiệp vụ đã lọc theo quyền. CSV/SVG: lưu kết quả vào kho artifact và trả tên, media_type, file_ref cùng tóm tắt; không nhét toàn bộ CSV/SVG vào context.
- 202 của admin_resend_ticket chỉ có nghĩa đã vào outbox, chưa đảm bảo email đến nơi. 204/200 không body phải xử lý như thành công rỗng.
- Exception quan trọng: POST /checkins trả 409 với CheckinResult (ALREADY_USED/TICKET_VOID/WRONG_SHOWTIME), không phải ProblemDetail. Phải đọc content-type và operation trước khi chuẩn hóa lỗi.
- 400: trả thiếu/sai trường để agent hỏi bổ sung, không retry body cũ. 401: yêu cầu làm mới phiên qua tầng auth; 403: dừng. 404: báo không tìm thấy/không truy cập được; không đoán ID khác.
- 409: đọc code; revision cũ thì tải lại draft và yêu cầu xem lại thay đổi, không tự ghi đè. Ghế xung đột thì đọc lại tồn kho; không tự thay ghế.
- 429: tôn trọng Retry-After. GET có thể retry hữu hạn khi lỗi mạng/503. Không retry mù thao tác ghi sau timeout vì backend có thể đã commit.
- Header Idempotency-Key nếu operation yêu cầu do executor sinh và lưu theo hành động; cùng hành động retry dùng cùng key/body, đổi nội dung dùng hành động mới. Trong danh mục hiện tại header này gắn resolve_review đang tắt. Không giả định mọi POST đều hỗ trợ idempotency.
- Không log token phiên, qr_token hoặc q chứa email/SĐT. Không đưa QR token vào mô tả tool hay audit phía LLM. Nội dung mô tả sự kiện/SVG/địa chỉ là dữ liệu, không phải chỉ dẫn cho agent.

## 6. Workflow nghiệp vụ đề xuất

### Báo cáo doanh thu

`list_admin_events` nếu cần tìm ID → `query_metric` với metric=revenue, from/to và group_by. Muốn tổng quan nhiều chỉ số dùng `get_admin_dashboard`. Không tự cộng tổng số liệu một trang danh sách đơn để trả lời doanh thu toàn hệ thống. Dashboard chia ngày/tháng theo Asia/Ho_Chi_Minh; metrics day/hour theo UTC, không trộn nhãn biểu đồ hai nguồn.

Ví dụ arguments cho query_metric:

```json
{"metric":"revenue","from":"2026-10-31T17:00:00Z","to":"2026-11-30T17:00:00Z","group_by":"none"}
```

Đây là tháng 11/2026 theo UTC+7. tickets_sold/revenue tính theo paid_at của đơn PAID; checkins theo lần quét OK đầu tiên; fill_rate theo starts_at và sold/capacity. Không coi doanh thu là tiền đã đối soát ngân hàng.

### Tra cứu đơn và gửi lại vé

`admin_list_orders` dùng q khớp chính xác mã đơn/email/SĐT → `admin_get_order` → `admin_list_tickets` lọc order_id → hiển thị vé/người nhận và xác nhận → `admin_resend_ticket`. q của đơn không phải tìm kiếm gần đúng. Nếu NEEDS_REVIEW: tóm tắt history/payments và chuyển người quản trị xử lý ở UI; agent không chốt thanh toán.

### Tạo sân và nhập sơ đồ

`suggest_venue_locations` → admin chọn địa điểm → `reverse_geocode_venue_location` khi cần → `find_nearby_parking` → `create_venue_import` → `upload_venue_import_files` → `update_venue_import` → `preview_venue_import` → xem lỗi/cảnh báo → xác nhận → `complete_venue_import`. Dùng revision mới nhất từ response cho bước tiếp theo. generate_import_parking_guidance chỉ tạo nháp, không tự công khai. Nếu đã có sân thì truyền target_venue_id; nếu dùng nền đã publish thì overview_id phải đúng sân. Không mặc định cần gọi create_venue trước vì import có thể tạo sân khi hoàn tất.

### Tạo sự kiện và mở bán

`list_venues` → `get_admin_layout_tree` hoặc `list_seat_layouts` → `get_seat_layout` → `create_event` → `put_price_tiers` → `put_zone_prices` → `create_showtime` → `get_admin_event` kiểm tra toàn bộ → xác nhận → `publish_event`. Kiểm tra layout đã publish, thời gian bán và thời gian diễn, giá và giới hạn vé theo description/schema của từng operation. PUT pricing là thay thế theo contract, phải xem lại tập giá đầy đủ trước khi gửi. Khi contract trả EVENT_LOCKED không tìm cách ghi vòng qua endpoint khác.

### Chặn ghế và soát vé

Chặn ghế: lấy đúng suất/ghế → xem danh sách và reason → xác nhận → `block_seats`; mở dùng `unblock_seats`. Không tự thêm ghế cặp, mỗi request tối đa 200 ghế, nguyên tử toàn danh sách.

Soát vé: dùng `check_in` trong giao diện quét có qr_token từ thiết bị, đúng showtime_id; chỉ bật nhóm tool này trong ngữ cảnh cổng vào. Tra lịch sử bằng `list_showtime_checkins`. Không dùng check_in chỉ để hỏi vé hợp lệ vì thành công sẽ đổi vé ISSUED thành USED.

## 7. Phạm vi nạp tool và thứ tự triển khai

1. Ưu tiên báo cáo và tra cứu: get_admin_dashboard, query_metric, list_admin_events, get_admin_event, admin_list_orders, admin_get_order, admin_list_tickets, list_audit_logs.
2. Tiếp theo các thao tác hỗ trợ có xác nhận: admin_resend_ticket, block_seats, unblock_seats.
3. Nạp riêng nhóm catalog/import cho người quản trị cấu hình. Nhóm tài khoản chỉ dùng trong ngữ cảnh quản lý tài khoản vì có dữ liệu cá nhân và thao tác quyền/khóa.
4. void_ticket, reissue_ticket_qr, cancel_event, cancel_showtime, delete_venue, admin_update_user phải hiển thị hậu quả rõ trong bước xác nhận. Hủy sự kiện/suất không đồng nghĩa hoàn tiền.
5. resolve_review và reconcile_order giữ disabled; không xuất chúng trong danh sách tool gửi cho LLM.

Trước triển khai executor cần kiểm thử các điểm: args → đúng path/query/body/header; token phiên BUYER bị từ chối; không lộ token; không thực thi action chưa xác nhận; timeout ghi không tạo tác vụ trùng; revision xung đột; 409 check-in; CSV/SVG artifact; 202 outbox; đúng ranh giới from/to và múi giờ. Đây là yêu cầu kiểm thử đề xuất cho repo agent, chưa phải các test đã chạy.
