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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vingo.api.model.HoldRequest;
import vingo.api.model.ListMyOrders200Response;
import vingo.api.model.Order;
import vingo.api.model.OrderStatus;
import vingo.booking.internal.BookingRepository;
import vingo.booking.internal.BookingRepository.HoldItem;
import vingo.booking.internal.BookingRepository.OrderPosition;
import vingo.booking.internal.BookingRepository.PendingOrder;
import vingo.booking.internal.OrderReader;
import vingo.realtime.RealtimeEventType;
import vingo.realtime.RealtimeService;
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
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration holdTtl;

    public BookingService(BookingRepository repository, OrderReader orders, CurrentUser currentUser,
                          RealtimeService realtime, IdempotencyService idempotency,
                          ApplicationEventPublisher events, Clock clock,
                          @Value("${vingo.hold.ttl}") String holdTtl) {
        this.repository = repository;
        this.orders = orders;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.idempotency = idempotency;
        this.events = events;
        this.clock = clock;
        this.holdTtl = Duration.parse(holdTtl);
    }

    /** Khoá đơn bằng SELECT FOR UPDATE trong transaction của payment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OrderForPayment> lockOrderForPayment(UUID orderId) {
        return repository.lockOrder(orderId);
    }

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

    @Transactional(propagation = Propagation.MANDATORY)
    public void markNeedsReview(UUID orderId, ReviewReason reason) {
        var order = repository.lockOrder(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        if (!List.of("PENDING", "EXPIRED", "CANCELLED").contains(order.status())) {
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

    @Transactional(propagation = Propagation.MANDATORY)
    public List<OrderLine> orderLines(UUID orderId) {
        return repository.orderLines(orderId);
    }

    public Order getOrder(UUID orderId) {
        UUID ownerId = repository.findOrderOwner(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        currentUser.requireOwner(ownerId);
        return orders.readOrder(orderId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

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
            var last = page.getLast();
            response.nextCursor(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
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

    @Transactional
    public Order holdSeats(UUID showtimeId, UUID idempotencyKey, HoldRequest request) {
        UUID userId = currentUser.require().id();
        // Gắn suất vào endpoint để cùng key ở suất khác có phạm vi độc lập.
        String endpoint = "POST /showtimes/{id}/holds:" + showtimeId;
        return idempotency.execute(userId, endpoint, idempotencyKey, request, Order.class, 201,
                () -> createHold(userId, showtimeId, request));
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
                throw new SeatTakenException(taken, repository.suggestSeats(showtimeId, seatIds, now));
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

    /** Chuyển tối đa {@code limit} đơn PENDING quá hạn thành EXPIRED trong một transaction, bỏ qua đơn đang bị khoá và trả số đơn đã chuyển. */
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
