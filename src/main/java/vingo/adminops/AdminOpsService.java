package vingo.adminops;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.adminops.internal.AuditLogRepository;
import vingo.adminops.internal.PaymentReadRepository;
import vingo.api.model.AdminGetOrder200Response;
import vingo.api.model.CancelEventRequest;
import vingo.api.model.ListAuditLogs200Response;
import vingo.api.model.ListMyOrders200Response;
import vingo.api.model.ListMyTickets200Response;
import vingo.api.model.Order;
import vingo.api.model.OrderStatus;
import vingo.api.model.ResolveReviewRequest;
import vingo.api.model.ResolveReviewRequest.ActionEnum;
import vingo.api.model.Ticket;
import vingo.api.model.TicketStatus;
import vingo.booking.BookingService;
import vingo.notifications.NotificationsService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.idempotency.IdempotencyService;
import vingo.shared.logging.LogKeys;
import vingo.shared.security.CurrentUser;
import vingo.ticketing.TicketingService;

/** Điều phối thao tác admin cùng audit, tồn kho, vé và outbox trong một transaction. */
@Service
public class AdminOpsService {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsService.class);

    private final BookingService booking;
    private final TicketingService ticketing;
    private final NotificationsService notifications;
    private final AuditLogRepository auditLogs;
    private final PaymentReadRepository payments;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;
    private final Clock clock;

    /** Nhận API gốc của các module để giữ ranh giới và transaction nghiệp vụ chung. */
    public AdminOpsService(BookingService booking, TicketingService ticketing, NotificationsService notifications,
                           AuditLogRepository auditLogs, PaymentReadRepository payments, IdempotencyService idempotency,
                           CurrentUser currentUser, Clock clock) {
        this.booking = booking;
        this.ticketing = ticketing;
        this.notifications = notifications;
        this.auditLogs = auditLogs;
        this.payments = payments;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Kiểm quyền admin rồi tra cứu đơn mọi buyer qua API booking. */
    @Transactional(readOnly = true)
    public ListMyOrders200Response adminListOrders(
            OrderStatus status, UUID eventId, UUID showtimeId, String q, String cursor, Integer limit) {
        requireAdmin();
        return booking.adminListOrders(status, eventId, showtimeId, q, cursor, limit);
    }

    /** Kiểm quyền admin rồi ghép chi tiết đơn, history và payments không chứa URL thanh toán. */
    @Transactional(readOnly = true)
    public AdminGetOrder200Response adminGetOrder(UUID orderId) {
        requireAdmin();
        var detail = booking.adminGetOrder(orderId);
        var order = detail.order();
        return new AdminGetOrder200Response().id(order.getId()).code(order.getCode()).status(order.getStatus())
                .event(order.getEvent()).showtime(order.getShowtime()).items(order.getItems())
                .totalAmount(order.getTotalAmount()).currency(AdminGetOrder200Response.CurrencyEnum.VND)
                .expiresAt(order.getExpiresAt()).paidAt(order.getPaidAt()).buyer(order.getBuyer())
                .reviewReason(order.getReviewReason() == null ? null
                        : AdminGetOrder200Response.ReviewReasonEnum.fromValue(order.getReviewReason().getValue()))
                .createdAt(order.getCreatedAt()).history(detail.history()).payments(payments.readPayments(orderId));
    }

    /** Kiểm quyền admin rồi tra cứu vé qua API ticketing luôn ẩn QR. */
    @Transactional(readOnly = true)
    public ListMyTickets200Response adminListTickets(
            String code, UUID orderId, UUID showtimeId, TicketStatus status, String cursor, Integer limit) {
        requireAdmin();
        return ticketing.adminListTickets(code, orderId, showtimeId, status, cursor, limit);
    }

    /** Xử lý review dưới khoá đơn, lưu kết quả idempotent cùng mọi thay đổi và audit. */
    @Transactional
    public Order resolveReview(UUID orderId, UUID idempotencyKey, ResolveReviewRequest request) {
        UUID adminId = requireAdmin();
        return idempotency.execute(adminId, "resolve-review:" + orderId, idempotencyKey, request, Order.class, 200, () -> {
            OffsetDateTime now = now();
            Order order = booking.resolveReview(orderId, adminId, request.getAction(), request.getReason(), now);
            if (request.getAction() == ActionEnum.ISSUE_TICKETS) {
                var ticketIds = ticketing.issueForOrder(orderId);
                booking.publishOrderPaid(orderId);
                booking.buyerEmailForOrder(orderId).ifPresent(email -> notifications.enqueueTickets(email, ticketIds));
            }
            auditLogs.insert(adminId, "RESOLVE_REVIEW", "order", orderId, request.getReason(), now,
                    Map.of("status", "NEEDS_REVIEW"), Map.of("status", order.getStatus().getValue()));
            logAction("RESOLVE_REVIEW", LogKeys.ORDER_ID, orderId, adminId);
            return order;
        });
    }

    /** Vô hiệu vé ISSUED và ghi audit cùng transaction, không trả ghế về kho. */
    @Transactional
    public Ticket voidTicket(UUID ticketId, CancelEventRequest request) {
        UUID adminId = requireAdmin();
        OffsetDateTime now = now();
        Ticket ticket = ticketing.voidTicket(ticketId, now);
        auditLogs.insert(adminId, "VOID_TICKET", "ticket", ticketId, request.getReason(), now,
                Map.of("status", "ISSUED"), Map.of("status", "VOID"));
        logAction("VOID_TICKET", "ticket_id", ticketId, adminId);
        return ticket;
    }

    /** Cấp lại QR cho vé ISSUED, audit chỉ lưu trạng thái để không lộ token. */
    @Transactional
    public Ticket reissueTicketQr(UUID ticketId, CancelEventRequest request) {
        UUID adminId = requireAdmin();
        OffsetDateTime now = now();
        Ticket ticket = ticketing.reissueTicketQr(ticketId);
        auditLogs.insert(adminId, "REISSUE_QR", "ticket", ticketId, request.getReason(), now,
                Map.of("status", "ISSUED"), Map.of("status", "ISSUED"));
        logAction("REISSUE_QR", "ticket_id", ticketId, adminId);
        return ticket;
    }

    /** Ghi outbox cho một vé ISSUED và audit bằng outbox_id, không áp rate limit của buyer. */
    @Transactional
    public void adminResendTicket(UUID ticketId) {
        UUID adminId = requireAdmin();
        OffsetDateTime now = now();
        UUID orderId = ticketing.lockIssuedTicketOrder(ticketId);
        String email = booking.buyerEmailForOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.ORDER_STATE_INVALID, "Đơn không có email để gửi vé"));
        UUID outboxId = notifications.enqueueTickets(email, List.of(ticketId));
        auditLogs.insert(adminId, "RESEND_TICKET", "ticket", ticketId, null, now,
                null, Map.of("outbox_id", outboxId));
        logAction("RESEND_TICKET", "ticket_id", ticketId, adminId);
    }

    /** Liệt kê audit theo bộ lọc và keyset at/id giảm dần, chỉ dành cho admin. */
    @Transactional(readOnly = true)
    public ListAuditLogs200Response listAuditLogs(String entity, UUID entityId, String cursor, Integer limit) {
        requireAdmin();
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "limit phải từ 1 đến 100");
        }
        Cursor after = cursor == null ? null : decodeCursor(cursor);
        var logs = auditLogs.find(entity, entityId, after == null ? null : after.at(),
                after == null ? null : after.id(), size + 1);
        var page = logs.subList(0, Math.min(size, logs.size()));
        var result = new ListAuditLogs200Response().data(page);
        if (logs.size() > size) {
            var last = page.getLast();
            result.nextCursor(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.getAt() + "|" + last.getId()).getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }

    private UUID requireAdmin() {
        var user = currentUser.require();
        if (!user.isAdmin()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Chỉ admin được thực hiện thao tác này");
        }
        return user.id();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Cursor decodeCursor(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor format");
            }
            UUID id = UUID.fromString(parts[1]);
            if (!id.toString().equalsIgnoreCase(parts[1])) {
                throw new IllegalArgumentException("Invalid cursor UUID");
            }
            return new Cursor(OffsetDateTime.parse(parts[0]), id);
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cursor không hợp lệ");
        }
    }

    private static void logAction(String action, String entityKey, UUID entityId, UUID adminId) {
        log.atInfo().addKeyValue("action", action).addKeyValue(entityKey, entityId)
                .addKeyValue(LogKeys.USER_ID, adminId).log("Đã thực hiện thao tác admin");
    }

    private record Cursor(OffsetDateTime at, UUID id) {}
}
