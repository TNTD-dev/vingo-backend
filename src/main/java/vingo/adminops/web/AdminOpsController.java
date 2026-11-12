package vingo.adminops.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.adminops.AdminOpsService;
import vingo.api.AdminOperationsApi;
import vingo.api.model.AdminGetOrder200Response;
import vingo.api.model.CancelEventRequest;
import vingo.api.model.ListAuditLogs200Response;
import vingo.api.model.ListMyOrders200Response;
import vingo.api.model.ListMyTickets200Response;
import vingo.api.model.Order;
import vingo.api.model.OrderStatus;
import vingo.api.model.ResolveReviewRequest;
import vingo.api.model.Ticket;
import vingo.api.model.TicketStatus;

/** Ánh xạ các thao tác admin; reconcileOrder giữ default 501 của contract. */
@RestController
public class AdminOpsController implements AdminOperationsApi {

    private final AdminOpsService adminOps;

    /** Nhận use case admin để controller chỉ ánh xạ HTTP. */
    public AdminOpsController(AdminOpsService adminOps) {
        this.adminOps = adminOps;
    }

    /** Trả trang đơn mọi buyer sau khi service kiểm quyền admin. */
    @Override
    public ResponseEntity<ListMyOrders200Response> adminListOrders(
            OrderStatus status, UUID eventId, UUID showtimeId, String q, String cursor, Integer limit) {
        return ResponseEntity.ok(adminOps.adminListOrders(status, eventId, showtimeId, q, cursor, limit));
    }

    /** Trả chi tiết đơn cùng lịch sử và payments không chứa URL thanh toán. */
    @Override
    public ResponseEntity<AdminGetOrder200Response> adminGetOrder(UUID orderId) {
        return ResponseEntity.ok(adminOps.adminGetOrder(orderId));
    }

    /** Trả trang vé admin không chứa QR, kể cả vé ISSUED. */
    @Override
    public ResponseEntity<ListMyTickets200Response> adminListTickets(
            String code, UUID orderId, UUID showtimeId, TicketStatus status, String cursor, Integer limit) {
        return ResponseEntity.ok(adminOps.adminListTickets(code, orderId, showtimeId, status, cursor, limit));
    }

    /** Trả đơn sau khi transaction xử lý review hoặc replay idempotency hoàn tất. */
    @Override
    public ResponseEntity<Order> resolveReview(UUID orderId, UUID idempotencyKey, ResolveReviewRequest request) {
        return ResponseEntity.ok(adminOps.resolveReview(orderId, idempotencyKey, request));
    }

    /** Trả vé đã vô hiệu, không kèm QR. */
    @Override
    public ResponseEntity<Ticket> voidTicket(UUID ticketId, CancelEventRequest request) {
        return ResponseEntity.ok(adminOps.voidTicket(ticketId, request));
    }

    /** Trả vé ISSUED có QR mới sau khi ghi audit. */
    @Override
    public ResponseEntity<Ticket> reissueTicketQr(UUID ticketId, CancelEventRequest request) {
        return ResponseEntity.ok(adminOps.reissueTicketQr(ticketId, request));
    }

    /** Xác nhận đã đưa email vé vào outbox, chưa xác nhận gửi email thành công. */
    @Override
    public ResponseEntity<Void> adminResendTicket(UUID ticketId) {
        adminOps.adminResendTicket(ticketId);
        return ResponseEntity.accepted().build();
    }

    /** Trả trang nhật ký theo entity, entity_id và cursor. */
    @Override
    public ResponseEntity<ListAuditLogs200Response> listAuditLogs(
            String entity, UUID entityId, String cursor, Integer limit) {
        return ResponseEntity.ok(adminOps.listAuditLogs(entity, entityId, cursor, limit));
    }
}
