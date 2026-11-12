package vingo.adminops.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.adminops.AdminOpsService;
import vingo.api.AdminOperationsApi;
import vingo.api.model.CancelEventRequest;
import vingo.api.model.ListAuditLogs200Response;
import vingo.api.model.Order;
import vingo.api.model.ResolveReviewRequest;
import vingo.api.model.Ticket;

/** Ánh xạ các thao tác admin đã triển khai; operation còn lại giữ default 501 của contract. */
@RestController
public class AdminOpsController implements AdminOperationsApi {

    private final AdminOpsService adminOps;

    /** Nhận use case admin để controller chỉ ánh xạ HTTP. */
    public AdminOpsController(AdminOpsService adminOps) {
        this.adminOps = adminOps;
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
