# EV6–EV7 — Công bố và nghiệm thu tạo sự kiện

## Hành vi triển khai
EventPublishingService giữ khóa event rồi các suất. Event DRAFT phải có suất chưa hủy,
layout PUBLISHED thuộc đúng hall của sân còn hoạt động, thời gian hợp lệ, và mọi khu có giá thuộc event.
Thiếu cấu hình trả 422 EVENT_INCOMPLETE, không thay đổi event/suất/tồn kho.
ENDED/CANCELLED trả EVENT_LOCKED; publish lại PUBLISHED không ghi lại.
Công bố event và đổi suất DRAFT sang SCHEDULED trong cùng transaction.
Tồn kho ghế và khu đứng được bổ sung bằng ON CONFLICT DO NOTHING: giữ nguyên BLOCKED/HELD/SOLD và bộ đếm.
Phát CatalogChanged sau khi ghi, listener Search xử lý AFTER_COMMIT.
Trạng thái ON_SALE tính khi đọc theo Clock, không thêm job hoặc lưu ON_SALE.

## Test nghiệm thu
AdminEventAcceptanceTest đi qua HTTP tạo DRAFT → thêm suất với layout đã công bố → hạng giá
→ gán giá cho cả khu ngồi/khu đứng → khóa ghế → công bố → đọc public → tới giờ mở bán.
Có kiểm tra publish lặp, giữ BLOCKED, bổ sung ghế thiếu, thiếu giá rollback, 401/403/404/409.
AdminSeatBlockingIntegrationTest có tranh chấp block/hold 10 lượt và kiểm tra đơn/tồn kho/lịch sử.
Focused suite: 13 tests xanh (10 EV5 + 3 EV6/EV7).

## Review và triển khai
Người dùng yêu cầu hoàn thành backend tới EV7 rồi push nhánh feat/create_events-luannt để Đức review.
Chưa nghiệm thu Swagger staging hoặc có phê duyệt contract EV4–EV7; đây là bước sau review/deploy.
Không chỉnh source module Booking/Search. Không gửi tin nhắn cho team tự động.
Hai poster mobile/desktop vẫn giữ riêng; mapping poster public còn theo bàn giao EV2, cần Đức kiểm tra khi review.

## Kết quả cuối
- mvnw clean verify: exit 0, 531 tests, 0 failures, 0 errors, 0 skipped (bao gồm ModularityTest).
- git diff --check: đạt.
- Log local: .local-handoff/ev7-verify.log (không commit log).

