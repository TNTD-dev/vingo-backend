package vingo.ticketing;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vingo.api.model.CheckInRequest;
import vingo.api.model.CheckinResult;
import vingo.api.model.CheckinResult.ResultEnum;
import vingo.api.model.ListMyTickets200Response;
import vingo.api.model.ListShowtimeCheckins200Response;
import vingo.api.model.Ticket;
import vingo.api.model.TicketStatus;
import vingo.booking.BookingService;
import vingo.notifications.NotificationsService;
import vingo.realtime.RealtimeEventType;
import vingo.realtime.RealtimeService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.logging.LogKeys;
import vingo.shared.ratelimit.RateLimiter;
import vingo.shared.security.CurrentUser;
import vingo.ticketing.internal.QrCodec;
import vingo.ticketing.internal.QrPng;
import vingo.ticketing.internal.TicketingReader;
import vingo.ticketing.internal.TicketingRepository;
import vingo.ticketing.internal.TicketingRepository.ManagedTicket;
import vingo.ticketing.internal.TicketingRepository.PagePosition;

/** API công khai để payment phát vé trong transaction chốt bán và soát vé tại cổng. */
@Service
public class TicketingService {

    private static final Logger log = LoggerFactory.getLogger(TicketingService.class);
    private static final SecureRandom random = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final BookingService booking;
    private final TicketingRepository repository;
    private final TicketingReader reader;
    private final QrCodec qrCodec;
    private final CurrentUser currentUser;
    private final RealtimeService realtime;
    private final NotificationsService notifications;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public TicketingService(BookingService booking, TicketingRepository repository, TicketingReader reader,
                            QrCodec qrCodec, CurrentUser currentUser, RealtimeService realtime,
                            NotificationsService notifications, RateLimiter rateLimiter, Clock clock) {
        this.booking = booking;
        this.repository = repository;
        this.reader = reader;
        this.qrCodec = qrCodec;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** Tra cứu vé cho adminops theo keyset id giảm dần, đọc theo lô và luôn ẩn QR. */
    @Transactional(readOnly = true)
    public ListMyTickets200Response adminListTickets(
            String code, UUID orderId, UUID showtimeId, TicketStatus status, String cursor, Integer limit) {
        int size = pageSize(limit);
        var ids = repository.adminListTickets(code, orderId, showtimeId,
                status == null ? null : status.getValue(), cursor == null ? null : decodeUuidCursor(cursor), size + 1);
        var page = ids.subList(0, Math.min(size, ids.size()));
        var tickets = reader.readTickets(page, now());
        tickets.forEach(ticket -> ticket.setQrToken(null));
        var response = new ListMyTickets200Response().data(tickets);
        if (ids.size() > size) {
            response.nextCursor(page.getLast().toString());
        }
        return response;
    }

    /** Payment gọi sau markSold = SOLD trong transaction IPN; khoá đơn PAID, phát vé idempotent và một sự kiện ticket.issued. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> issueForOrder(UUID orderId) {
        var order = booking.lockOrderForPayment(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        if (!"PAID".equals(order.status())) {
            throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Chỉ phát vé cho đơn PAID");
        }
        var existing = repository.ticketIdsForOrder(orderId);
        if (!existing.isEmpty()) {
            return existing;
        }
        List<UUID> ids = new ArrayList<>();
        for (var line : booking.orderLines(orderId)) {
            int quantity = line.seatId() == null ? line.quantity() : 1;
            for (int seq = 1; seq <= quantity; seq++) {
                while (true) {
                    var id = repository.insertTicket(orderId, line, seq, randomCode());
                    if (id.isPresent()) {
                        ids.add(id.get());
                        break;
                    }
                }
            }
        }
        realtime.publish(order.userId(), RealtimeEventType.TICKET_ISSUED,
                Map.of("order_id", orderId, "ticket_ids", ids));
        booking.buyerEmailForOrder(orderId).ifPresent(email -> notifications.enqueueTickets(email, ids));
        log.atInfo().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue(LogKeys.USER_ID, order.userId())
                .addKeyValue("ticket_count", ids.size()).log("Đã phát {} vé", ids.size());
        return List.copyOf(ids);
    }

    /** Tạo ảnh PNG từ QR hiện tại sau khi kiểm chủ vé và trạng thái ISSUED. */
    @Transactional(readOnly = true)
    public byte[] ticketQrPng(UUID ticketId) {
        Ticket ticket = getTicket(ticketId);
        if (ticket.getStatus() == TicketStatus.USED) {
            throw new DomainException(ErrorCode.TICKET_USED, "Vé đã sử dụng");
        }
        if (ticket.getStatus() != TicketStatus.ISSUED) {
            throw new DomainException(ErrorCode.TICKET_NOT_VALID, "Vé không còn hiệu lực");
        }
        return QrPng.encode(ticket.getQrToken());
    }

    /** Khoá và vô hiệu vé ISSUED trong transaction audit của adminops, giữ nguyên ghế SOLD. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket voidTicket(UUID ticketId, OffsetDateTime now) {
        lockIssuedTicket(ticketId);
        repository.voidTicket(ticketId);
        return reader.readTickets(List.of(ticketId), now).getFirst();
    }

    public record QrReissueResult(Ticket ticket, int version) {}

    /** Khoá vé ISSUED và tăng qr_version trong transaction audit của adminops. */
    @Transactional(propagation = Propagation.MANDATORY)
    public QrReissueResult reissueTicketQr(UUID ticketId) {
        lockIssuedTicket(ticketId);
        int version = repository.reissueQr(ticketId);
        var ticket = reader.readTickets(List.of(ticketId), now()).getFirst().qrToken(null);
        return new QrReissueResult(ticket, version);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lockIssuedTicketOrder(UUID ticketId) {
        return lockIssuedTicket(ticketId).orderId();
    }

    @Transactional
    public void resendMyTicket(UUID ticketId) {
        UUID userId = currentUser.require().id();
        rateLimiter.check(userId, "ticket-resend", 3, Duration.ofHours(1));
        var ticket = lockTicket(ticketId);
        currentUser.requireOwner(ticket.userId());
        requireIssued(ticket);
        String email = booking.buyerEmailForOrder(ticket.orderId())
                .orElseThrow(() -> new DomainException(ErrorCode.ORDER_STATE_INVALID, "Đơn không có email để gửi vé"));
        notifications.enqueueTickets(email, List.of(ticketId));
        log.atInfo().addKeyValue("action", "RESEND_MY_TICKET").addKeyValue("ticket_id", ticketId)
                .addKeyValue(LogKeys.USER_ID, userId).log("Đã đưa email vé vào outbox");
    }

    private ManagedTicket lockTicket(UUID ticketId) {
        return repository.lockTicket(ticketId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy vé"));
    }

    private ManagedTicket lockIssuedTicket(UUID ticketId) {
        var ticket = lockTicket(ticketId);
        requireIssued(ticket);
        return ticket;
    }

    private static void requireIssued(ManagedTicket ticket) {
        if ("USED".equals(ticket.status())) {
            throw new DomainException(ErrorCode.TICKET_USED, "Vé đã sử dụng");
        }
        if (!"ISSUED".equals(ticket.status())) {
            throw new DomainException(ErrorCode.TICKET_NOT_VALID, "Vé không còn hiệu lực");
        }
    }

    @Transactional(readOnly = true)
    public Ticket getTicket(UUID ticketId) {
        UUID owner = repository.ticketOwner(ticketId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy vé"));
        currentUser.requireOwner(owner);
        return reader.readTickets(List.of(ticketId), now()).stream().findFirst()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy vé"));
    }

    @Transactional(readOnly = true)
    public ListMyTickets200Response listMyTickets(String when, TicketStatus status, String cursor, Integer limit) {
        UUID userId = currentUser.require().id();
        int size = pageSize(limit);
        String window = when == null ? "upcoming" : when;
        if (!"upcoming".equals(window) && !"past".equals(window)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "when phải là upcoming hoặc past");
        }
        OffsetDateTime now = now();
        var ids = repository.listTickets(userId, "past".equals(window),
                status == null ? null : status.getValue(),
                cursor == null ? null : decodeUuidCursor(cursor), now, size + 1);
        var page = ids.subList(0, Math.min(size, ids.size()));
        var result = new ListMyTickets200Response().data(reader.readTickets(page, now));
        if (ids.size() > size) {
            result.nextCursor(page.getLast().toString());
        }
        return result;
    }

    /** Kiểm HMAC QR, khoá vé theo id, đổi ISSUED thành USED và ghi mọi lượt quét (kể cả INVALID_QR). */
    @Transactional
    public CheckinResult checkIn(CheckInRequest request) {
        UUID scanner = currentUser.require().id();
        OffsetDateTime now = now();

        var parsed = qrCodec.verify(request.getQrToken());
        if (parsed.isEmpty()) {
            repository.insertCheckin(null, request.getShowtimeId(), scanner, request.getGate(),
                    ResultEnum.INVALID_QR, now);
            return new CheckinResult().result(ResultEnum.INVALID_QR).scannedAt(now);
        }

        var locked = repository.lockTicketForScan(parsed.get().ticketId());
        if (locked.isEmpty() || locked.get().qrVersion() != parsed.get().qrVersion()) {
            repository.insertCheckin(null, request.getShowtimeId(), scanner, request.getGate(),
                    ResultEnum.INVALID_QR, now);
            return new CheckinResult().result(ResultEnum.INVALID_QR).scannedAt(now);
        }

        var ticket = locked.get();
        ResultEnum outcome;
        if ("VOID".equals(ticket.status())) {
            outcome = ResultEnum.TICKET_VOID;
        } else if (!ticket.showtimeId().equals(request.getShowtimeId())) {
            outcome = ResultEnum.WRONG_SHOWTIME;
        } else if ("USED".equals(ticket.status())) {
            outcome = ResultEnum.ALREADY_USED;
        } else {
            repository.markUsed(ticket.id(), now);
            outcome = ResultEnum.OK;
        }
        repository.insertCheckin(ticket.id(), request.getShowtimeId(), scanner, request.getGate(), outcome, now);
        log.atInfo().addKeyValue("ticket_id", ticket.id()).addKeyValue(LogKeys.USER_ID, scanner)
                .addKeyValue("result", outcome.getValue()).log("Check-in {}", outcome.getValue());
        return new CheckinResult()
                .result(outcome)
                .ticketCode(ticket.code())
                .zone(ticket.zone())
                .row(ticket.row())
                .number(ticket.number())
                .scannedAt(now)
                .firstUsedAt(outcome == ResultEnum.ALREADY_USED ? ticket.usedAt() : null);
    }

    @Transactional(readOnly = true)
    public ListShowtimeCheckins200Response listShowtimeCheckins(UUID showtimeId, String cursor, Integer limit) {
        int size = pageSize(limit);
        var positions = repository.listCheckins(showtimeId, cursor == null ? null : decodeCursor(cursor), size + 1);
        var page = positions.subList(0, Math.min(size, positions.size()));
        var result = new ListShowtimeCheckins200Response()
                .data(reader.readCheckins(page.stream().map(PagePosition::id).toList()));
        if (positions.size() > size) {
            result.nextCursor(encodeCursor(page.getLast()));
        }
        return result;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static int pageSize(Integer limit) {
        return limit == null ? 20 : limit;
    }

    private static String randomCode() {
        var code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private static UUID decodeUuidCursor(String cursor) {
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cursor không hợp lệ");
        }
    }

    private static String encodeCursor(PagePosition position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (position.at() + "|" + position.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static PagePosition decodeCursor(String cursor) {
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
            return new PagePosition(id, OffsetDateTime.parse(parts[0]));
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Cursor không hợp lệ");
        }
    }
}
