# CLAUDE.md — vingo-backend

File này là quy tắc bắt buộc khi viết code trong repo. Đọc hết trước khi sửa bất kỳ file nào.
Thiết kế hệ thống: đọc `docs/architecture.html` (C4, ERD, sequence 5a–5e, state machine, bảng mã lỗi, sự kiện SSE). Bản online mới nhất: https://claude.ai/artifact/5RTS81Ssp79nLF9DxVCUwj. Khi code và tài liệu mâu thuẫn, hỏi lại, không tự đoán.

## 1. Dự án

Backend của Vingo — app đặt vé sự kiện qua chat. Repo này cung cấp REST API + SSE cho web và cho AI agent (repo `vingo-agent`).

- Java 25, Spring Boot 4.1 (Spring MVC, virtual threads), Spring Modulith, Spring Security
- PostgreSQL 18 (nguồn sự thật duy nhất), Flyway, Redis 7 (chỉ cache đọc + rate limit)
- Maven wrapper, JUnit 5, Testcontainers
- Một file jar, hai profile: `api` (phục vụ HTTP) và `worker` (chạy job nền)

## 2. Lệnh hay dùng

```bash
./mvnw verify                                   # sinh code từ contract, build, chạy toàn bộ test
./mvnw -q generate-sources                      # chỉ sinh lại interface từ vingo-openapi.yaml
docker compose -f deploy/compose.dev.yaml up -d # Postgres 18 + Redis cho máy local
./mvnw spring-boot:run -Dspring-boot.run.profiles=api,local
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker,local
./mvnw test -Dtest=ModularityTest               # kiểm tra ranh giới module
```

Luôn chạy `./mvnw verify` trước khi báo xong việc. Không báo xong khi test đỏ.

## 3. Contract-first

- `contracts/vingo-openapi.yaml` là nguồn sự thật của API.
- Muốn thêm/sửa endpoint: **sửa yaml trước**, chạy `generate-sources`, rồi mới viết code.
- Controller **phải** `implements` interface sinh ra trong `vn.vingo.api`. Không tự viết `@RequestMapping` cho endpoint public.
- Không sửa file trong `target/generated-sources`. Không commit code sinh.
- Mỗi operation trong yaml phải có `description` rõ ràng và ví dụ lỗi: AI agent đọc mô tả này để quyết định gọi tool.

## 4. Cấu trúc module

Mỗi package con trực tiếp của `vn.vingo` là một module (Spring Modulith).

```
<module>/
├── XxxService.java        # API công khai — module khác chỉ được gọi class ở đây
├── *Result.java, *Exception.java
├── web/                   # controller (implements interface sinh từ yaml)
└── internal/              # repository, entity, job — CHỈ module này dùng
```

Quy tắc:

- Module khác **chỉ** gọi class ở gốc package. Không import `web.*` hay `internal.*` của module khác. `ModularityTest` sẽ fail.
- `shared` là module mở, chỉ chứa hạ tầng (security, lỗi, logging, config). `shared` **không** import module nghiệp vụ.
- Không tạo vòng phụ thuộc giữa module.
- Truyền id (UUID) giữa module, không truyền entity.

| Module | Chủ | Ghi chú |
|---|---|---|
| auth, catalog, payment | Luân | payment cần 2 người review |
| search, chat | Đức | |
| booking, ticketing, adminops, realtime | Danh | booking cần 2 người review |
| notifications, reporting | Tiến | |

## 5. Truy cập dữ liệu và transaction

- `booking`, `payment`, `ticketing`, `realtime`: **chỉ dùng `JdbcClient`** với SQL viết tay. Không dùng JPA ở các module này.
- `catalog`, `auth`, `adminops`, `reporting`, `notifications`, `search`: được dùng Spring Data JPA cho CRUD; truy vấn phức tạp dùng `JdbcClient`.
- Một use case không trộn JPA và `JdbcClient` trên cùng bảng.
- `@Transactional` đặt ở method của `XxxService` (use case). Repository không tự mở transaction, không tự commit.
- Gọi module khác bên trong một use case thì chạy chung transaction (ví dụ IPN gọi `booking.markSold()`, `ticketing.issueForOrder()`, `notifications.enqueue()`, `realtime.publish()`).
- Không gọi HTTP ra ngoài (VNPay, email, LLM) bên trong transaction đang giữ lock.
- Khoá chính dùng `uuidv7()` của PostgreSQL. Tiền là `long`/`int` đơn vị VND, không dùng `double`. Thời gian là `OffsetDateTime`/`timestamptz`, lưu UTC.

## 6. Quy tắc nghiệp vụ không được phá

Đây là phần dễ sai nhất. Viết test trước, cho người duyệt test, rồi mới viết code.

**Giữ ghế (booking)**
- Ghế trống = `status = 'AVAILABLE'` **hoặc** `status = 'HELD' AND held_until < now()` (Implicit Status). Không dùng Redis để giữ ghế.
- Giữ ghế bằng một câu `UPDATE ... WHERE (điều kiện trống) RETURNING seat_id`. Số dòng trả về < số ghế yêu cầu → ném `SeatTakenException` để rollback cả đơn (giữ tất cả hoặc không giữ gì).
- Khu đứng: `UPDATE showtime_zone_stock SET held = held + :n WHERE capacity - sold - held >= :n`.
- Tạo đơn PENDING và giữ ghế trong **cùng một transaction**. Hạn giữ 10 phút.
- Mỗi user tối đa 1 đơn PENDING cho mỗi suất.

**Thanh toán (payment)**
- **Chỉ IPN hoặc querydr được chốt bán.** Trang return chỉ để hiển thị, không đổi trạng thái gì.
- IPN: kiểm chữ ký HMAC-SHA512 trước (sai → `97`). Sau đó `SELECT ... FOR UPDATE` đơn, ghi `payment_transaction` (raw).
- IPN trùng (intent đã `SUCCEEDED`) → trả `02`, không làm gì thêm.
- Tiền về nhưng đơn hết hạn/huỷ/sai số tiền → đơn `NEEDS_REVIEW`, trả `00`.
- Không bao giờ tin số tiền từ client; so `vnp_Amount/100` với `orders.total_amount`.
- Chốt ghế: `UPDATE showtime_seat SET status='SOLD' WHERE order_id = :id AND status='HELD'`; thiếu ghế → `NEEDS_REVIEW`.

**Job nền (profile worker)**
- `expire_holds` (5s), `reconcile_payments` (1 phút), `outbox_sender` (2s), `realtime_gc`.
- Chọn dòng bằng `FOR UPDATE SKIP LOCKED`. Mỗi job có ShedLock để không chạy trùng.
- Job phải idempotent: chạy lại không gây sai.

**Trạng thái suất diễn**: `ON_SALE`, `SALES_CLOSED`, `FINISHED` tính khi đọc từ `sale_opens_at`, `sale_closes_at`, `ends_at`. Chỉ lưu `DRAFT`, `SCHEDULED`, `CANCELLED`.

**Không có hoàn tiền, không đổi ghế** trong hệ thống. Không thêm tính năng này.

## 7. API, lỗi, idempotency

- Base path `/api/v1`, JSON, tiền là số nguyên VND, thời gian ISO 8601 UTC.
- Lỗi trả `ProblemDetail` (RFC 9457) kèm trường `code`. Ném `DomainException(ErrorCode.X, ...)`, để `GlobalExceptionHandler` chuyển thành response. Không tự build response lỗi trong controller.
- Mã lỗi đã có (thêm mới phải cập nhật yaml + bảng này):

| HTTP | code | Khi nào |
|---|---|---|
| 409 | SEAT_TAKEN | Ghế vừa bị giữ/bán (kèm gợi ý ghế khác) |
| 409 | NOT_ENOUGH_STOCK | Khu đứng không đủ chỗ |
| 409 | SHOWTIME_NOT_ON_SALE | Suất chưa mở / đã đóng bán |
| 409 | ORDER_STATE_INVALID | Sai trạng thái đơn |
| 409 | HOLD_EXPIRED | Đơn quá hạn giữ ghế |
| 409 | IDEMPOTENCY_CONFLICT | Trùng Idempotency-Key, khác body |
| 422 | PROFILE_REQUIRED | Chưa có hồ sơ người mua |
| 422 | MAX_TICKETS_EXCEEDED | Vượt số vé tối đa mỗi đơn |
| 423 | ACCOUNT_LOCKED | Sai mật khẩu quá số lần |
| 429 | RATE_LIMITED | Gọi quá nhanh |

- `Idempotency-Key` bắt buộc cho: tạo hold, tạo payment, resolve-review. Cùng key + cùng body → trả kết quả cũ; khác body → 409.
- Phân trang bằng cursor: `?cursor=&limit=20` → `{ data, next_cursor }`.

## 8. Bảo mật

- Mọi endpoint phải khai báo quyền: public, `BUYER`, hoặc `ADMIN`. Endpoint `/admin/**` chỉ `ADMIN`.
- Lấy user hiện tại qua `CurrentUser` trong `shared.security`, không đọc header tay.
- Kiểm tra quyền sở hữu: buyer chỉ xem/huỷ đơn và vé **của mình**.
- Không log: mật khẩu, token, refresh token, OTP, `vnp_HashSecret`, số điện thoại đầy đủ.
- Chỉ lưu hash của mật khẩu (Argon2), OTP và refresh token.
- Secret chỉ đọc từ biến môi trường. Không commit `.env`.
- Validate input bằng Bean Validation trên model sinh từ yaml.

## 9. Log và giám sát

- Log JSON. Mọi log trong request có `request_id` (MDC). Thêm `order_id`, `user_id`, `conversation_id` khi có.
- Log ở mức INFO cho chuyển trạng thái quan trọng (đơn PAID, NEEDS_REVIEW, vé phát hành). Không log body request.
- Lỗi không mong đợi để Sentry bắt, không nuốt exception.

## 10. Migration (Flyway)

- File trong `src/main/resources/db/migration/`, tên `V<yyyyMMdd_HHmm>__<module>_<việc>.sql`, ví dụ `V20261111_1030__booking_init.sql`.
- Không sửa migration đã merge vào `main`. Muốn đổi thì tạo file mới.
- Viết đủ constraint tài liệu yêu cầu: unique, CHECK, partial index (ví dụ `CHECK (sold + held <= capacity)`, unique vé theo `(showtime_id, seat_id) WHERE seat_id IS NOT NULL AND status <> 'VOID'`).

## 11. Test

- Test integration kế thừa `support/IntegrationTest` (Testcontainers PostgreSQL 18). Không dùng H2.
- Mỗi endpoint có test cho nhánh thành công và từng mã lỗi 4xx nó trả.
- Booking bắt buộc có test tranh chấp: 50 luồng cùng giữ 1 ghế → đúng 1 thành công.
- Payment bắt buộc test: chữ ký sai, IPN trùng, sai tiền, tiền về sau hạn, IPN chạy cùng lúc với `expire_holds`.
- Dùng `Clock` inject được để test thời gian, không gọi `now()` trực tiếp trong Java.
- Tên test mô tả hành vi: `holdSeats_returns409_whenSeatAlreadyHeld`.

## 12. Code style

- Dùng `record` cho DTO và kết quả. Không dùng Lombok.
- Constructor injection, field `final`. Không `@Autowired` trên field.
- Không trả `null` từ service; dùng `Optional` hoặc ném `DomainException`.
- Tên class, method, biến bằng tiếng Anh. Comment và commit message được viết tiếng Việt.
- Không thêm thư viện mới khi chưa hỏi.

## 13. Việc không được làm

- Không sửa module của người khác khi chưa báo chủ module.
- Không đổi `vingo-openapi.yaml` mà không qua PR (Đức duyệt).
- Không dùng Redis làm nguồn sự thật cho ghế hay đơn.
- Không chốt thanh toán ở trang return.
- Không tắt, skip hay xoá test để build xanh.
- Không thêm endpoint ngoài contract.

## 14. Definition of Done

- [ ] Khớp `vingo-openapi.yaml`, lỗi có `code` đúng bảng
- [ ] Migration đủ constraint
- [ ] Test integration xanh trên Postgres thật, kể cả các lỗi 4xx
- [ ] Phân quyền đúng, kiểm tra quyền sở hữu
- [ ] Log có `request_id` và id nghiệp vụ, không log dữ liệu nhạy cảm
- [ ] `./mvnw verify` xanh, `ModularityTest` xanh
- [ ] Gọi thử thành công trên Swagger ở staging
