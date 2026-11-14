package vingo.booking;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.api.model.AdminGetOrder200Response;
import vingo.api.model.HoldRequest;
import vingo.api.model.ListMyOrders200Response;
import vingo.api.model.Order;
import vingo.api.model.OrderStatus;
import vingo.api.model.ResolveReviewRequest.ActionEnum;
import vingo.booking.internal.BookingRepository;
import vingo.booking.internal.BookingRepository.HoldItem;
import vingo.booking.internal.BookingRepository.OrderPosition;
import vingo.booking.internal.BookingRepository.PendingOrder;
import vingo.booking.internal.OrderReader;
import vingo.booking.internal.SeatHoldConflict;
import vingo.realtime.RealtimeEventType;
import vingo.realtime.RealtimeService;
import vingo.search.SearchService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.idempotency.IdempotencyService;
import vingo.shared.logging.LogKeys;
import vingo.shared.security.CurrentUser;

/** Xử lý giữ chỗ và chuyển trạng thái đơn trong transaction nghiệp vụ. */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final SecureRandom random = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final BookingRepository repository;
    private final OrderReader orders;
    private final CurrentUser currentUser;
    private final RealtimeService realtime;
    private final IdempotencyService idempotency;
    private final SearchService search;
    private final TransactionTemplate holdTransaction;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration holdTtl;

    public BookingService(BookingRepository repository, OrderReader orders, CurrentUser currentUser,
                          RealtimeService realtime, IdempotencyService idempotency,
                          SearchService search, PlatformTransactionManager transactionManager,
                          ApplicationEventPublisher events, Clock clock,
                          @Value("${vingo.hold.ttl}") String holdTtl) {
        this.repository = repository;
        this.orders = orders;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.idempotency = idempotency;
        this.search = search;
        this.holdTransaction = new TransactionTemplate(transactionManager);
        this.events = events;
        this.clock = clock;
        this.holdTtl = Duration.parse(holdTtl);
    }

    /** Tra cứu đơn cho adminops, kiểm q/limit và đọc cả trang theo keyset created_at/id. */
    @Transactional(readOnly = true)
    public ListMyOrders200Response adminListOrders(
            OrderStatus status, UUID eventId, UUID showtimeId, String q, String cursor, Integer limit) {
        // limit 1..100 đã được validation của interface sinh từ contract kiểm (sai → 400).
        int size = limit == null ? 20 : limit;
        String query = q == null ? null : q.trim();
        if (query != null && (query.length() < 3 || query.length() > 254)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "q sau trim phải dài 3–254 ký tự");
        }
        var positions = repository.adminListOrders(status == null ? null : status.getValue(), eventId,
                showtimeId, query, cursor == null ? null : decodeCursor(cursor), size + 1);
        var page = positions.subList(0, Math.min(size, positions.size()));
        var response = new ListMyOrders200Response().data(orders.readOrders(
                page.stream().map(OrderPosition::id).toList(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        if (positions.size() > size) {
            response.nextCursor(encodeCursor(page.getLast()));
        }
        return response;
    }

    /** Đọc đơn cùng history cho adminops (chưa có payments), ném NOT_FOUND nếu không có đơn. */
    @Transactional(readOnly = true)
    public AdminGetOrder200Response adminGetOrder(UUID orderId) {
        return orders.readAdminOrder(orderId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * Đọc và khoá đơn bằng {@code SELECT FOR UPDATE} đến khi transaction của payment kết thúc.
     * Phải có transaction đang chạy ({@code MANDATORY}); không kiểm trạng thái hay quyền sở hữu.
     *
     * @param orderId đơn cần khoá trước khi đối chiếu thanh toán
     * @return dữ liệu đơn với trạng thái DB hiện tại, hoặc rỗng nếu không tồn tại
     * @throws org.springframework.transaction.IllegalTransactionStateException nếu chưa có transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OrderForPayment> lockOrderForPayment(UUID orderId) {
        return repository.lockOrder(orderId);
    }

    /** Adminops giữ transaction chung cho lấy lại tồn kho, chuyển trạng thái, history và phát vé. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order resolveReview(UUID orderId, UUID adminId, ActionEnum action, String reason, OffsetDateTime now) {
        var order = repository.lockOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        if (!order.status().equals("NEEDS_REVIEW")) {
            throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Chỉ xử lý đơn NEEDS_REVIEW");
        }
        Objects.requireNonNull(action, "action");
        String target;
        if (action == ActionEnum.ISSUE_TICKETS) {
            if (!repository.canIssueReviewedOrder(order.showtimeId(), now)) {
                throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Suất đã kết thúc hoặc đã bị huỷ");
            }
            long requestedSeats = repository.orderLines(orderId).stream().filter(line -> line.seatId() != null).count();
            if (repository.reclaimReviewSeats(orderId, order.showtimeId(), now) != requestedSeats) {
                throw new DomainException(ErrorCode.SEAT_TAKEN, "Không thể lấy lại toàn bộ ghế cũ của đơn");
            }
            if (!repository.reclaimReviewStanding(orderId, order.showtimeId())) {
                throw new DomainException(ErrorCode.NOT_ENOUGH_STOCK, "Khu đứng không đủ chỗ để phát vé");
            }
            repository.markPaid(orderId, now);
            target = "PAID";
        } else {
            repository.markRefunded(orderId, now);
            target = "REFUNDED";
        }
        repository.insertHistory(orderId, "NEEDS_REVIEW", target, adminId, reason, now);
        return orders.readOrder(orderId, now);
    }

    /** Đọc email của đơn trong transaction gửi vé; rỗng khi đơn không khai báo email. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> buyerEmailForOrder(UUID orderId) {
        return repository.buyerEmailForOrder(orderId);
    }

    /** Adminops gọi sau khi phát vé để order.paid theo sau ticket.issued trong cùng transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOrderPaid(UUID orderId) {
        var order = repository.lockOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        if (!order.status().equals("PAID")) {
            throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Chỉ thông báo đơn đã PAID");
        }
        realtime.publish(order.userId(), RealtimeEventType.ORDER_PAID, Map.of("order_id", orderId));
    }

    /**
     * Chốt bán đơn {@code PENDING} trong transaction hiện có ({@code MANDATORY}): bán tồn kho,
     * chuyển đơn sang {@code PAID}, ghi lịch sử và sự kiện {@code order.paid}.
     * Nếu thiếu tồn kho, chuyển cả đơn sang {@code NEEDS_REVIEW} với lý do {@code PAID_AFTER_EXPIRY}.
     * Không tự kiểm số tiền hay {@code expiresAt}; đơn PENDING quá hạn vẫn bán được nếu còn đủ tồn kho.
     *
     * <p>Trong IPN đã xác thực: {@link #lockOrderForPayment} → đối chiếu số tiền →
     * {@code markSold} hoặc {@link #markNeedsReview} → {@link #orderLines} để phát vé chỉ khi
     * kết quả là {@link SaleOutcome#SOLD}. Với {@code NEEDS_REVIEW}, không phát vé.
     * IPN/querydr mới được chốt bán; không gọi từ trang return.
     *
     * @param orderId đơn PENDING đã được payment kiểm tra
     * @return {@code SOLD} khi đơn đã PAID; {@code NEEDS_REVIEW} khi thiếu tồn kho, chưa bán
     * @throws DomainException với {@code NOT_FOUND} nếu không có đơn;
     *         {@code ORDER_STATE_INVALID} nếu đơn không PENDING
     * @throws org.springframework.transaction.IllegalTransactionStateException nếu chưa có transaction
     * @throws IllegalStateException nếu không bán được khu đứng sau khi đã khoá và kiểm đủ tồn kho
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SaleOutcome markSold(UUID orderId) {
        var order = repository.lockOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        if (!order.status().equals("PENDING")) {
            throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Chỉ được bán đơn PENDING");
        }
        // Kiểm hết tồn kho dưới khoá trước khi bán để đơn có cả ghế và khu đứng không bị bán dở.
        boolean seatsAvailable = repository.lockSeatsForSale(orderId, order.showtimeId());
        boolean standingAvailable = repository.lockStandingForSale(orderId, order.showtimeId());
        if (!seatsAvailable || !standingAvailable) {
            markNeedsReview(orderId, ReviewReason.PAID_AFTER_EXPIRY);
            return SaleOutcome.NEEDS_REVIEW;
        }
        boolean standingSold = repository.sellStanding(orderId, order.showtimeId());
        if (!standingSold) {
            throw new IllegalStateException("Không thể bán khu đứng sau khi đã khoá và kiểm đủ tồn kho cho đơn "
                    + orderId);
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        repository.sellSeats(orderId);
        repository.markPaid(orderId, now);
        repository.insertHistory(orderId, "PENDING", "PAID", null, now);
        realtime.publish(order.userId(), RealtimeEventType.ORDER_PAID, Map.of("order_id", orderId));
        logTransition(orderId, order.userId(), "PENDING", "PAID");
        return SaleOutcome.SOLD;
    }

    /**
     * Chuyển đơn {@code PENDING}, {@code EXPIRED} hoặc {@code CANCELLED} sang {@code NEEDS_REVIEW},
     * lưu lý do, lịch sử và sự kiện {@code order.needs_review} trong transaction hiện có ({@code MANDATORY}).
     * Với đơn PENDING, nhả tồn kho còn giữ; người gọi không phát vé cho đơn NEEDS_REVIEW.
     *
     * @param orderId đơn có tiền về nhưng cần xử lý ngoại lệ
     * @param reason lý do xử lý, không được null
     * @throws DomainException với {@code NOT_FOUND} nếu không có đơn;
     *         {@code ORDER_STATE_INVALID} nếu trạng thái không thuộc ba trạng thái cho phép
     * @throws NullPointerException nếu reason là null sau khi đơn đã qua kiểm tra trạng thái
     * @throws org.springframework.transaction.IllegalTransactionStateException nếu chưa có transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markNeedsReview(UUID orderId, ReviewReason reason) {
        markNeedsReview(orderId, reason, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markDuplicatePaymentForReview(UUID orderId) {
        markNeedsReview(orderId, ReviewReason.AMOUNT_MISMATCH, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSuspectedPaymentForReview(UUID orderId) {
        markNeedsReview(orderId, ReviewReason.PAYMENT_SUSPECTED, true);
    }

    private void markNeedsReview(UUID orderId, ReviewReason reason, boolean duplicatePayment) {
        var order = repository.lockOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        if (!List.of("PENDING", "EXPIRED", "CANCELLED").contains(order.status())
                && !(duplicatePayment && "PAID".equals(order.status()))) {
            throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Đơn không thể chuyển sang NEEDS_REVIEW");
        }
        Objects.requireNonNull(reason, "reason");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (order.status().equals("PENDING")) {
            repository.releaseSeats(orderId);
            repository.releaseStanding(orderId, order.showtimeId());
        }
        repository.markNeedsReview(orderId, reason, now);
        repository.insertHistory(orderId, order.status(), "NEEDS_REVIEW", null, now);
        realtime.publish(order.userId(), RealtimeEventType.ORDER_NEEDS_REVIEW,
                Map.of("order_id", orderId, "reason", reason.name()));
        logTransition(orderId, order.userId(), order.status(), "NEEDS_REVIEW");
    }

    /**
     * Đọc các dòng vé trong transaction hiện có ({@code MANDATORY}), theo thứ tự id dòng đơn.
     * Không kiểm trạng thái hay quyền sở hữu; luồng thanh toán chỉ dùng để phát vé sau kết quả {@code SOLD}.
     *
     * @param orderId đơn cần đọc dòng vé
     * @return các dòng ghế ngồi/khu đứng; rỗng nếu không có dòng hoặc không tồn tại đơn
     * @throws org.springframework.transaction.IllegalTransactionStateException nếu chưa có transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OrderLine> orderLines(UUID orderId) {
        return repository.orderLines(orderId);
    }

    /**
     * Đọc chi tiết đơn của người đang đăng nhập, gồm các dòng vé; không đổi trạng thái đơn quá hạn.
     *
     * @param orderId đơn cần xem
     * @return chi tiết đơn với trạng thái đang lưu trong DB
     * @throws DomainException với {@code NOT_FOUND} nếu đơn không tồn tại hoặc không thuộc người gọi;
     *         {@code UNAUTHORIZED} nếu cần kiểm chủ sở hữu nhưng chưa đăng nhập
     */
    public Order getOrder(UUID orderId) {
        UUID ownerId = repository.findOrderOwner(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        currentUser.requireOwner(ownerId);
        return orders.readOrder(orderId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * Liệt kê đơn của người đang đăng nhập, mới nhất trước theo {@code created_at} rồi id.
     * Người gọi phải cung cấp limit hợp lệ (API giới hạn 1–100); hàm không tự kiểm giới hạn này.
     *
     * @param status trạng thái DB cần lọc, hoặc null để lấy mọi trạng thái
     * @param cursor con trỏ từ trang trước, hoặc null để lấy trang đầu
     * @param limit số đơn tối đa mỗi trang, null dùng 20
     * @return trang chi tiết đơn và nextCursor khi còn trang tiếp theo
     * @throws DomainException với {@code UNAUTHORIZED} nếu chưa đăng nhập;
     *         {@code VALIDATION_ERROR} nếu cursor không hợp lệ
     */
    public ListMyOrders200Response listMyOrders(OrderStatus status, String cursor, Integer limit) {
        UUID userId = currentUser.require().id();
        int pageSize = limit == null ? 20 : limit;
        OrderPosition after = cursor == null ? null : decodeCursor(cursor);
        var positions = repository.listMyOrders(userId, status == null ? null : status.getValue(),
                after, pageSize + 1);
        boolean hasMore = positions.size() > pageSize;
        var page = positions.subList(0, Math.min(pageSize, positions.size()));
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var response = new ListMyOrders200Response()
                .data(orders.readOrders(page.stream().map(OrderPosition::id).toList(), now));
        if (hasMore) {
            response.nextCursor(encodeCursor(page.getLast()));
        }
        return response;
    }

    private static String encodeCursor(OrderPosition position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (position.createdAt() + "|" + position.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static OrderPosition decodeCursor(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor format");
            }
            UUID id = UUID.fromString(parts[1]);
            if (!id.toString().equalsIgnoreCase(parts[1])) {
                throw new IllegalArgumentException("Invalid cursor UUID");
            }
            return new OrderPosition(id, OffsetDateTime.parse(parts[0]));
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cursor không hợp lệ");
        }
    }

    /**
     * Khoá và huỷ đơn {@code PENDING} của người đang đăng nhập, kể cả khi đã quá hạn giữ.
     * Trong cùng transaction, nhả tồn kho, chuyển đơn sang {@code CANCELLED}, ghi lịch sử
     * và phát đồng bộ {@link OrderCancelled} để listener tham gia transaction huỷ.
     *
     * @param orderId đơn PENDING cần huỷ
     * @return chi tiết đơn sau khi chuyển sang CANCELLED
     * @throws DomainException với {@code NOT_FOUND} nếu đơn không tồn tại hoặc không thuộc người gọi;
     *         {@code UNAUTHORIZED} nếu cần kiểm chủ sở hữu nhưng chưa đăng nhập;
     *         {@code ORDER_STATE_INVALID} nếu đơn không PENDING
     */
    @Transactional
    public Order cancelOrder(UUID orderId) {
        var order = repository.lockOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        currentUser.requireOwner(order.userId());
        if (!order.status().equals("PENDING")) {
            throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Chỉ được huỷ đơn PENDING");
        }
        // PENDING quá expires_at vẫn được huỷ vì job chưa chuyển trạng thái và nhả tồn kho.
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        repository.releaseSeats(orderId);
        repository.releaseStanding(orderId, order.showtimeId());
        repository.cancelOrder(orderId, now);
        repository.insertHistory(orderId, "PENDING", "CANCELLED", order.userId(), now);
        events.publishEvent(new OrderCancelled(orderId, order.userId()));
        Order result = orders.readOrder(orderId, now);
        logTransition(orderId, order.userId(), "PENDING", "CANCELLED");
        return result;
    }

    /**
     * Tạo đơn {@code PENDING} và giữ toàn bộ ghế hoặc số chỗ khu đứng trong cùng transaction,
     * với hạn giữ theo cấu hình {@code vingo.hold.ttl}; tự thêm ghế còn lại của cặp ghế đôi.
     * Cần đăng nhập và suất đang mở bán. Đơn PENDING cũ quá hạn của cùng người/suất được đóng trước;
     * đơn còn hạn ngăn tạo mới. Thất bại thì rollback toàn bộ; gợi ý ghế được đọc sau rollback.
     *
     * @param showtimeId suất cần giữ chỗ
     * @param idempotencyKey key trong phạm vi người dùng và suất; cùng key/body trả kết quả cũ trong 24 giờ
     * @param request một trong hai dạng: seatIds, hoặc zoneId kèm quantity
     * @return đơn vừa tạo, hoặc kết quả thành công đã lưu cho cùng key/body
     * @throws SeatTakenException với {@code SEAT_TAKEN} nếu không giữ được đủ ghế
     * @throws DomainException với {@code UNAUTHORIZED} nếu chưa đăng nhập;
     *         {@code VALIDATION_ERROR} nếu yêu cầu hoặc ghế/khu không hợp lệ;
     *         {@code NOT_FOUND} nếu suất không tồn tại hoặc DRAFT;
     *         {@code SHOWTIME_NOT_ON_SALE} nếu suất chưa/không còn mở bán hoặc khu chưa có giá;
     *         {@code MAX_TICKETS_EXCEEDED} nếu vượt số vé tối đa, kể cả ghế đôi được thêm;
     *         {@code ORDER_STATE_INVALID} nếu đã có đơn PENDING còn hạn;
     *         {@code NOT_ENOUGH_STOCK} nếu khu đứng thiếu chỗ;
     *         {@code IDEMPOTENCY_CONFLICT} nếu key còn hiệu lực có body khác hoặc thiếu kết quả đã lưu
     */
    public Order holdSeats(UUID showtimeId, UUID idempotencyKey, HoldRequest request) {
        UUID userId = currentUser.require().id();
        // Gắn suất vào endpoint để cùng key ở suất khác có phạm vi độc lập.
        String endpoint = "POST /showtimes/{id}/holds:" + showtimeId;
        try {
            return holdTransaction.execute(status -> idempotency.execute(
                    userId, endpoint, idempotencyKey, request, Order.class, 201,
                    () -> createHold(userId, showtimeId, request)));
        } catch (SeatHoldConflict conflict) {
            // Chỉ đọc gợi ý sau rollback để không giữ lock và thấy cả ghế vừa nhả.
            var suggested = conflict.requestedByZone().entrySet().stream()
                    .flatMap(zone -> search.suggestSeats(showtimeId, zone.getKey(), zone.getValue()).stream())
                    .toList();
            throw new SeatTakenException(conflict.takenSeatIds(), suggested);
        }
    }

    private Order createHold(UUID userId, UUID showtimeId, HoldRequest request) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        validate(request);
        boolean seated = request.getSeatIds() != null && !request.getSeatIds().isEmpty();

        var sale = repository.findSale(showtimeId, now)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy suất diễn"));
        if (sale.status().equals("DRAFT")) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy suất diễn");
        }
        if (!sale.onSale()) {
            throw new DomainException(ErrorCode.SHOWTIME_NOT_ON_SALE, "Suất diễn không đang mở bán");
        }

        repository.lockPendingOrder(userId, showtimeId, now).ifPresent(old -> {
            if (!old.overdue()) {
                throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Đã có đơn đang giữ cho suất này");
            }
            repository.lockReplacementInventory(old, seated ? request.getSeatIds() : List.of(), request.getZoneId());
            expirePendingOrder(old, now);
        });

        List<HoldItem> items;
        if (seated) {
            var seatIds = new LinkedHashSet<>(request.getSeatIds());
            items = repository.seatItems(showtimeId, seatIds);
            requireAllSeats(items, seatIds.size());
            if (seatIds.addAll(repository.pairedSeats(seatIds))) {
                items = repository.seatItems(showtimeId, seatIds);
                requireAllSeats(items, seatIds.size());
            }
        } else {
            items = List.of(repository.standingItem(showtimeId, request.getZoneId(), request.getQuantity())
                    .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_ERROR,
                            "Khu đứng không thuộc suất diễn")));
        }
        int quantity = items.stream().mapToInt(HoldItem::quantity).sum();
        if (quantity > sale.maxTicketsPerOrder()) {
            throw new DomainException(ErrorCode.MAX_TICKETS_EXCEEDED, "Vượt số vé tối đa mỗi đơn");
        }
        long total = 0;
        for (var item : items) {
            if (item.priceTierId() == null) {
                throw new DomainException(ErrorCode.SHOWTIME_NOT_ON_SALE, "Suất diễn chưa có giá cho khu này");
            }
            total = Math.addExact(total, Math.multiplyExact(item.unitPrice(), item.quantity()));
        }

        OffsetDateTime expiresAt = now.plus(holdTtl);
        UUID orderId = createOrder(userId, showtimeId, total, expiresAt, now);
        if (seated) {
            var seatIds = items.stream().map(HoldItem::seatId).toList();
            var held = new HashSet<>(repository.holdSeats(showtimeId, seatIds, orderId, expiresAt, now));
            if (held.size() != seatIds.size()) {
                var taken = seatIds.stream().filter(id -> !held.contains(id)).toList();
                Map<UUID, Integer> requestedByZone = new LinkedHashMap<>();
                items.forEach(item -> requestedByZone.merge(item.zoneId(), item.quantity(), Integer::sum));
                throw new SeatHoldConflict(taken, requestedByZone);
            }
        } else {
            UUID zoneId = request.getZoneId();
            if (!repository.holdStanding(showtimeId, zoneId, quantity)) {
                throw new DomainException(ErrorCode.NOT_ENOUGH_STOCK, "Khu đứng không đủ chỗ", Map.of(
                        "seats_left", repository.seatsLeft(showtimeId, zoneId)));
            }
        }

        for (var item : items) {
            repository.insertItem(orderId, item);
        }
        repository.insertHistory(orderId, null, "PENDING", userId, now);
        realtime.publish(userId, RealtimeEventType.ORDER_HELD,
                Map.of("order_id", orderId, "expires_at", expiresAt));
        Order order = orders.readOrder(orderId, now);
        logTransition(orderId, userId, null, "PENDING");
        return order;
    }

    private static void validate(HoldRequest request) {
        if (request == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Thiếu yêu cầu giữ chỗ");
        }
        var seats = request.getSeatIds();
        boolean seated = seats != null && !seats.isEmpty();
        if (seated ? request.getZoneId() != null || request.getQuantity() != null
                || seats.size() > 20 || seats.stream().anyMatch(Objects::isNull)
                : request.getZoneId() == null || request.getQuantity() == null
                || request.getQuantity() < 1 || request.getQuantity() > 20) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Gửi seat_ids hoặc zone_id và quantity hợp lệ");
        }
    }

    private static void requireAllSeats(List<HoldItem> items, int count) {
        if (items.size() != count) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Ghế không thuộc khu ngồi của suất diễn");
        }
    }

    private UUID createOrder(UUID userId, UUID showtimeId, long total, OffsetDateTime expiresAt,
                             OffsetDateTime now) {
        while (true) {
            var code = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            try {
                var id = repository.insertOrder(code.toString(), userId, showtimeId, total, expiresAt, now);
                if (id.isPresent()) {
                    return id.get();
                }
            } catch (DuplicateKeyException ex) {
                if (String.valueOf(ex.getMostSpecificCause().getMessage())
                        .contains("orders_one_pending_per_showtime_uk")) {
                    throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Đã có đơn đang giữ cho suất này");
                }
                throw ex;
            }
        }
    }

    // Luôn khoá đơn trước tồn kho để tránh khoá chéo với hold, cancel và IPN.
    private void expirePendingOrder(PendingOrder order, OffsetDateTime now) {
        repository.releaseSeats(order.id());
        repository.releaseStanding(order.id(), order.showtimeId());
        repository.expireOrder(order.id(), now);
        repository.insertHistory(order.id(), "PENDING", "EXPIRED", null, now);
        realtime.publish(order.userId(), RealtimeEventType.ORDER_EXPIRED, Map.of("order_id", order.id()));
        logTransition(order.id(), order.userId(), "PENDING", "EXPIRED");
    }

    private static void logTransition(UUID orderId, UUID userId, String from, String to) {
        log.atInfo().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue(LogKeys.USER_ID, userId)
                .log("Order status {} -> {}", from, to);
    }

    /**
     * Chuyển tối đa {@code limit} đơn PENDING có {@code expires_at <= now} sang {@code EXPIRED}
     * trong cùng transaction; nhả tồn kho, ghi lịch sử và sự kiện {@code order.expired}.
     * Dùng {@code FOR UPDATE SKIP LOCKED} để bỏ qua đơn đang bị khoá; chạy lại không xử lý đơn đã EXPIRED.
     *
     * @param limit số đơn tối đa cần xử lý, phải không âm
     * @return số đơn đã chuyển sang EXPIRED trong lần gọi này
     */
    @Transactional
    public int expireOverdueHolds(int limit) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var overdue = repository.lockOverduePendingOrders(now, limit);
        for (var order : overdue) {
            expirePendingOrder(order, now);
        }
        return overdue.size();
    }
}
