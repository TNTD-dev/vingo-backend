# vingo-backend

Backend của **Vingo** — app đặt vé sự kiện bằng cách chat với AI.
Repo này cung cấp REST API và luồng realtime (SSE) cho:

- **Web** (`vingo-web`): giao diện chat, sơ đồ ghế, vé, dashboard
- **AI agent** (`vingo-agent`): Buyer agent và Admin Copilot gọi chính các API này bằng token của người dùng

Người mua: đăng nhập → tìm sự kiện → giữ ghế 10 phút → thanh toán VNPay → nhận vé QR → check-in tại cổng.
Admin: tạo sân, sơ đồ ghế, sự kiện, suất, giá → quản lý đơn và vé → xem báo cáo.

---

## Mục lục

1. [Tech stack](#tech-stack)
2. [Cài đặt và chạy trên máy](#cài-đặt-và-chạy-trên-máy)
3. [Cấu trúc repo](#cấu-trúc-repo)
4. [Module và người phụ trách](#module-và-người-phụ-trách)
5. [Contract API](#contract-api)
6. [Database và migration](#database-và-migration)
7. [Test](#test)
8. [Thanh toán VNPay khi dev](#thanh-toán-vnpay-khi-dev)
9. [Biến môi trường](#biến-môi-trường)
10. [Deploy](#deploy)
11. [Quy trình làm việc](#quy-trình-làm-việc)
12. [Xử lý sự cố thường gặp](#xử-lý-sự-cố-thường-gặp)

---

## Tech stack

| Thành phần | Công nghệ |
|---|---|
| Ngôn ngữ | Java 25 (LTS) |
| Framework | Spring Boot 4.1 — Spring MVC, virtual threads, Spring Security |
| Chia module | Spring Modulith (kiểm tra ranh giới module bằng test) |
| Database | PostgreSQL 18, Flyway |
| Cache | Redis 7 — chỉ cache đọc và rate limit, Redis sập hệ thống vẫn bán vé |
| API | OpenAPI contract-first, openapi-generator, Swagger UI (springdoc) |
| Realtime | Server-Sent Events + PostgreSQL `LISTEN/NOTIFY` |
| Job nền | `@Scheduled` + ShedLock, chạy ở profile `worker` |
| Thanh toán | VNPay (IPN + querydr) |
| Test | JUnit 5, Testcontainers, k6 |
| Quan sát | Log JSON, Micrometer, Sentry |
| Build | Maven wrapper (`./mvnw`) |

---

## Cài đặt và chạy trên máy

### Cần cài

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| JDK | 25 (Temurin) | Khuyên cài qua SDKMAN: `sdk install java 25-tem` |
| Docker | Bản mới | Cần cho Postgres/Redis local và cho Testcontainers |
| Python | 3.12+ | Chỉ để chạy `scripts/simulate_ipn.py` |

Không cần cài Maven, dùng `./mvnw` có sẵn trong repo.

### Chạy lần đầu

```bash
# 1. Clone và vào thư mục
git clone <url>/vingo-backend.git
cd vingo-backend

# 2. Tạo file môi trường
cp deploy/.env.example .env.local
# điền VNPAY_TMN_CODE, VNPAY_HASH_SECRET (sandbox), các giá trị khác để mặc định

# 3. Bật Postgres 18 + Redis
docker compose -f deploy/compose.dev.yaml up -d

# 4. Build + chạy toàn bộ test (lần đầu sẽ tải dependency, mất vài phút)
./mvnw verify

# 5. Chạy API (cổng 8080)
./mvnw spring-boot:run -Dspring-boot.run.profiles=api,local

# 6. (Terminal khác) Chạy worker — job nhả ghế, đối soát thanh toán, gửi email
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker,local

# 7. (Tuỳ chọn) Nạp dữ liệu mẫu
docker compose -f deploy/compose.dev.yaml exec -T postgres psql -U vingo -d vingo < scripts/seed.sql
```

Dữ liệu mẫu có 1 sân, 1 sự kiện đang mở bán (30 ghế + khu đứng 200 chỗ) và 2 tài khoản dev:
`admin@vingo.local` / `buyer@vingo.local`, mật khẩu `Vingo@2026`. Chỉ dùng trên máy local.

Mở Swagger UI: <http://localhost:8080/swagger-ui.html>

Flyway tự chạy migration khi API khởi động.

### Profile

Cùng một file jar, chọn vai trò bằng profile:

| Profile | Làm gì |
|---|---|
| `api` | Phục vụ HTTP (REST + SSE), tắt job nền |
| `worker` | Không mở cổng HTTP, chạy job nền |
| `local` | Cấu hình cho máy dev (đọc `.env.local`, log dạng dễ đọc) |

---

## Cấu trúc repo

```
vingo-backend/
├── contracts/
│   └── vingo-openapi.yaml            # định nghĩa toàn bộ API
├── docs/
│   └── architecture.html             # tài liệu kiến trúc Vingo (mở bằng trình duyệt)
├── deploy/
│   ├── compose.yaml                  # staging/production
│   ├── compose.dev.yaml              # Postgres + Redis cho máy dev
│   ├── Caddyfile                     # reverse proxy, TLS
│   └── .env.example
├── scripts/
│   ├── simulate_ipn.py               # giả kết quả VNPay
│   └── seed.sql                      # dữ liệu mẫu
├── .github/workflows/
│   ├── ci.yml                        # mỗi PR: ./mvnw verify
│   ├── deploy.yml                    # merge main → staging
│   └── contract.yml                  # tag contract-v* → GitHub Release
├── CLAUDE.md                         # quy tắc code (bắt buộc đọc)
├── Dockerfile
├── pom.xml
└── src/
    ├── main/java/vn/vingo/
    │   ├── VingoApplication.java
    │   ├── shared/                   # security, xử lý lỗi, logging, config
    │   ├── auth/  catalog/  payment/
    │   ├── search/  chat/
    │   ├── booking/  ticketing/  adminops/  realtime/
    │   └── notifications/  reporting/
    ├── main/resources/
    │   ├── application.yml
    │   ├── application-api.yml
    │   ├── application-worker.yml
    │   └── db/migration/
    └── test/java/vn/vingo/
        ├── ModularityTest.java
        ├── support/IntegrationTest.java
        └── <module>/...
```

### Bên trong một module

Mọi module theo cùng một khuôn. Ví dụ `booking`:

```
booking/
├── BookingService.java           # các hàm module khác được gọi
├── HoldResult.java               # dữ liệu trả về cho module khác
├── SeatTakenException.java       # → 409 SEAT_TAKEN
├── web/
│   └── HoldsController.java      # implements interface sinh từ yaml
└── internal/                     # chỉ module này dùng
    ├── SeatInventoryRepository.java
    ├── OrderRepository.java
    └── ExpireHoldsJob.java
```

**Quy tắc quan trọng nhất:** module khác chỉ được gọi class ở gốc package (`BookingService`, `HoldResult`). Gọi vào `web/` hay `internal/` của module khác thì `ModularityTest` fail và CI đỏ.

---

## Module và người phụ trách

| Module | Người | Làm gì |
|---|---|---|
| `auth` | Luân | Đăng ký, đăng nhập, OTP, đăng nhập Google, token |
| `catalog` | Luân | Sân, sơ đồ ghế, sự kiện, suất diễn, giá |
| `payment` | Luân | Tạo link VNPay, nhận IPN, đối soát querydr |
| `search` | Đức | Tìm sự kiện (gõ không dấu vẫn ra), sơ đồ ghế, số chỗ còn |
| `chat` | Đức | Hội thoại, tin nhắn, thao tác chờ xác nhận (từ Sprint 2) |
| `booking` | Danh | Giữ ghế, đơn hàng, huỷ đơn, nhả ghế hết hạn |
| `ticketing` | Danh | Phát vé, mã QR, check-in |
| `adminops` | Danh | Xử lý đơn lỗi (NEEDS_REVIEW), huỷ vé, gửi lại vé |
| `realtime` | Danh | Đẩy sự kiện cho client qua SSE `/me/stream` |
| `notifications` | Tiến | Gửi email OTP, email vé qua hàng đợi outbox |
| `reporting` | Tiến | Báo cáo: doanh thu, vé bán, tỉ lệ lấp đầy, check-in |
| `shared` | Luân (security), Đức (logging, config) | Hạ tầng dùng chung |

Muốn sửa module của người khác: báo chủ module trước. PR vào `booking` và `payment` cần 2 người duyệt.

### Các module gọi nhau thế nào

Luồng thanh toán thành công (IPN) chạy **trong một transaction**:

```
payment (nhận IPN)
  ├── booking.markSold(order)          # ghế HELD → SOLD
  ├── ticketing.issueForOrder(order)   # tạo vé + QR
  ├── notifications.enqueue(email vé)  # ghi hàng đợi email
  └── realtime.publish(order.paid)     # báo cho client
```

Một bước lỗi thì cả transaction rollback, không có chuyện đã trừ ghế mà không có vé.

---

## Contract API

`contracts/vingo-openapi.yaml` là nguồn sự thật của API. Web và agent sinh client từ chính file này.

### Thêm hoặc sửa endpoint

1. Sửa `contracts/vingo-openapi.yaml`, viết `description` rõ ràng (AI agent đọc mô tả này).
2. Chạy `./mvnw generate-sources`, interface mới xuất hiện trong `vn.vingo.api`.
3. Viết controller `implements` interface đó. Viết sai chữ ký thì không biên dịch được.
4. Mở PR, Đức duyệt phần thay đổi contract.
5. Sau khi merge, gắn tag để repo agent và web cập nhật:

```bash
git tag contract-v0.4.0 && git push origin contract-v0.4.0
```

### Quy ước chung

- Base path `/api/v1`, JSON
- Xác thực: header `Authorization: Bearer <access_token>`
- Tiền: số nguyên VND. Thời gian: ISO 8601 UTC
- Lỗi: `application/problem+json` có trường `code` (xem bảng mã lỗi trong `CLAUDE.md`)
- `Idempotency-Key` bắt buộc khi giữ ghế, tạo thanh toán, xử lý đơn lỗi
- Phân trang: `?cursor=&limit=20` → `{ data, next_cursor }`

---

## Database và migration

- Migration nằm ở `src/main/resources/db/migration/`, Flyway tự chạy khi API khởi động.
- Đặt tên theo ngày giờ để 4 người không trùng số version:

```
V20261111_1030__catalog_init.sql
V20261111_1415__booking_init.sql
```

- **Không sửa migration đã merge.** Cần đổi thì tạo file mới.
- Khoá chính dùng `uuidv7()` (có sẵn trong PostgreSQL 18).

Xem database local:

```bash
docker compose -f deploy/compose.dev.yaml exec postgres psql -U vingo -d vingo
```

---

## Test

```bash
./mvnw verify                                   # toàn bộ: unit + integration + ModularityTest
./mvnw test -Dtest=ModularityTest               # chỉ kiểm tra ranh giới module
./mvnw test -Dtest='vn.vingo.booking.**'        # test của một module
```

- Test integration chạy trên **PostgreSQL 18 thật** qua Testcontainers (cần Docker đang chạy). Không dùng H2.
- Kế thừa `support/IntegrationTest` để có sẵn database sạch và client gọi API.

Test bắt buộc trước khi đóng Sprint 1:

| Nhóm | Kiểm tra |
|---|---|
| Giữ ghế | 50 người cùng giữ 1 ghế → đúng 1 người được; khu đứng không bán quá sức chứa |
| Thanh toán | Chữ ký sai, IPN gửi 2 lần, sai số tiền, tiền về sau khi đơn hết hạn |
| Idempotency | Gửi lại cùng request không tạo 2 đơn |
| Module | `ModularityTest` xanh |

Load test (k6) nằm trong thư mục `perf/`, chạy trên staging:

```bash
k6 run perf/hold-seats.js -e BASE_URL=https://staging.<domain>
```

---

## Thanh toán VNPay khi dev

VNPay gửi kết quả (IPN) từ server của họ về URL HTTPS public, nên máy local không nhận được.

| Môi trường | Cách nhận kết quả thanh toán |
|---|---|
| Staging | Nhận IPN thật từ VNPay sandbox (IPN URL đã cấu hình trong merchant portal sandbox) |
| Máy local | Dùng `scripts/simulate_ipn.py` để giả IPN có chữ ký hợp lệ |

```bash
# Giả thanh toán thành công cho đơn
python scripts/simulate_ipn.py --order <order_code> --amount 500000 --result success

# Giả các trường hợp lỗi
python scripts/simulate_ipn.py --order <order_code> --amount 400000 --result success   # sai số tiền
python scripts/simulate_ipn.py --order <order_code> --result success --repeat 2         # IPN gửi 2 lần
python scripts/simulate_ipn.py --order <order_code> --bad-signature                     # chữ ký sai
```

Script đọc `VNPAY_HASH_SECRET` từ `.env.local`.

Lưu ý: trang return của VNPay **chỉ để hiển thị**. Trạng thái đơn chỉ đổi khi nhận IPN hoặc khi job đối soát gọi querydr.

---

## Biến môi trường

| Biến | Ví dụ | Mô tả |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `api` | `api` hoặc `worker` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/vingo` | PostgreSQL |
| `DB_USER`, `DB_PASSWORD` | `vingo` / `vingo` | |
| `REDIS_URL` | `redis://localhost:6379` | Cache đọc + rate limit |
| `JWT_SECRET` | (chuỗi ngẫu nhiên ≥ 32 byte) | Ký access token |
| `GOOGLE_CLIENT_ID` | `xxx.apps.googleusercontent.com` | Xác thực đăng nhập Google |
| `VNPAY_TMN_CODE` | (từ email sandbox) | Mã website VNPay |
| `VNPAY_HASH_SECRET` | (từ email sandbox) | Khoá ký HMAC-SHA512 |
| `VNPAY_PAY_URL` | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` | Trang thanh toán |
| `VNPAY_API_URL` | `https://sandbox.vnpayment.vn/merchant_webapi/api/transaction` | querydr |
| `VNPAY_RETURN_URL` | `https://<web-domain>/payment/result` | Trang return |
| `MAIL_API_KEY` | | Nhà cung cấp email |
| `MAIL_FROM` | `no-reply@<domain>` | |
| `SENTRY_DSN` | | Báo lỗi |

Không commit `.env*` (trừ `.env.example`). Secret production lưu trong GitHub Secrets.

---

## Deploy

```
merge vào main → GitHub Actions build image → đẩy lên GHCR → SSH vào server → docker compose pull && up -d
```

- Staging: tự deploy mỗi lần merge `main`.
- Production: deploy khi gắn tag `release-v*`.
- Server chạy: `api` ×2, `worker` ×1, Redis, Caddy (TLS tự động). PostgreSQL dùng dịch vụ managed, có backup hằng ngày và PITR.
- Flyway chạy migration trước khi `api` nhận request.

Kiểm tra sau deploy:

```bash
curl https://<domain>/actuator/health
```

---

## Quy trình làm việc

1. Mỗi task một nhánh, đặt tên `<tên>/<module>-<việc>`, ví dụ `danh/booking-hold-seats`. Nhánh sống không quá 1 ngày.
2. Dùng Claude Code: đọc `CLAUDE.md` trước. Với giữ ghế và thanh toán, viết test trước, duyệt test rồi mới viết code.
3. Trước khi mở PR: `./mvnw verify` phải xanh.
4. PR nhỏ, mô tả: làm gì, đổi contract không, test gì.
5. `booking` và `payment` cần 2 người duyệt; các module khác cần 1 người.
6. Merge xong kiểm tra lại trên Swagger ở staging.

---

## Xử lý sự cố thường gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Test báo không kết nối được Docker | Docker chưa chạy | Bật Docker Desktop rồi chạy lại |
| Không thấy class trong `vn.vingo.api` | Chưa sinh code từ yaml | `./mvnw generate-sources`, rồi reload project trong IDE |
| `ModularityTest` fail | Gọi vào `web/` hoặc `internal/` của module khác | Gọi qua `XxxService` ở gốc package |
| Flyway báo checksum mismatch | Sửa migration đã chạy | Trả file cũ về nguyên trạng, tạo migration mới |
| IPN luôn trả `97` | Sai `VNPAY_HASH_SECRET` hoặc proxy sửa tham số | Kiểm tra secret, không encode lại tham số trước khi ký |
| SSE ngắt sau ~60 giây | Proxy cắt kết nối rảnh | Kiểm tra heartbeat 15 giây và cấu hình Caddy |
| Đơn kẹt `PENDING` sau khi trả tiền | IPN không về | Kiểm tra worker đang chạy, job `reconcile_payments` sẽ gọi querydr |

---

## Tài liệu liên quan

| Tài liệu | Nội dung | Mở |
|---|---|---|
| Tài liệu kiến trúc Vingo | C4 (context, container, component), ERD, data flow, sequence 5a–5e, state machine, worker, agent, API, deploy, NFR | Bản trong repo: [`docs/architecture.html`](docs/architecture.html) (tải về rồi mở bằng trình duyệt) · Bản online: [claude.ai](https://claude.ai/artifact/5RTS81Ssp79nLF9DxVCUwj) |
| Board kiến trúc gốc | Các hình vẽ gốc trên Excalidraw | [Architecture Ticket System](https://app.excalidraw.com/s/1PR0Jqn44kd/4ANPdM39rZd) |
| Kế hoạch dự án | Sprint, phân công, tech stack, cấu trúc repo | [Vingo – Kế hoạch dự án](https://claude.ai/artifact/FjHzP5DeyDodd5aossroqk) |
| Quy tắc code | Bắt buộc đọc trước khi code | [`CLAUDE.md`](CLAUDE.md) |

Bản online là bản mới nhất. Khi kiến trúc thay đổi, cập nhật lại `docs/architecture.html` trong cùng PR.
