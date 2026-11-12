# EV5 — Khóa và mở ghế theo suất

Hai endpoint ADMIN block/unblock được triển khai trong AdminVenueController, gọi SeatBlockingService.
Transaction khóa theo thứ tự event → showtime → showtime_seat (seat_id tăng dần).
Toàn bộ danh sách được kiểm tra trước khi ghi; lỗi một ghế rollback toàn bộ.
AVAILABLE hoặc HELD đã quá hạn nghiêm ngặt (< Clock) được block. HELD còn hạn và SOLD trả SEAT_TAKEN.
Unblock chỉ chấp nhận AVAILABLE/BLOCKED. Gửi lại trạng thái đích là no-op, không thêm lịch sử.
Lý do và danh sách ghế thực sự đổi được lưu ở catalog_seat_block_log. Không log nội dung lý do.
CatalogChanged phát trong transaction; Search chỉ làm mới cache sau commit.
Suất DRAFT đã có tồn kho từ EV3. Không được đổi layout khi tồn kho còn BLOCKED.

## Bàn giao EV6
Publish không được xóa và dựng lại tồn kho: giữ nguyên BLOCKED và chỉ thêm dòng thiếu.
Cần test nối tiếp công bố sự kiện giữ nguyên ghế khóa khi triển khai EV6.
Contract cần PR Đức review; Swagger staging chưa kiểm tra trong phiên này.
Không chỉnh source module booking/search/realtime.

## Nghiệm thu bổ sung
AdminSeatBlockingIntegrationTest: 10 test xanh, bao gồm 10 lượt tranh chấp HTTP block với buyer hold.
Mỗi lượt chỉ một bên thắng; khi block thắng không còn đơn phát sinh, khi hold thắng ghế HELD có order_id.
AdminEventAcceptanceTest xác nhận publish không reset BLOCKED và tạo lại dòng tồn kho thiếu.
Kết quả verify toàn bộ và phạm vi staging xem event-management-ev7.md.
Theo chỉ đạo người dùng, review contract/staging được thực hiện sau khi push để Đức review.
