package vingo.notifications;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vingo.notifications.internal.OutboxRepository;

/** Caller giữ quyền gửi, danh sách vé và transaction nghiệp vụ của mình. */
@Service
public class NotificationsService {
    private final OutboxRepository outbox;
    private final Clock clock;
    public NotificationsService(OutboxRepository outbox,Clock clock) { this.outbox=outbox; this.clock=clock; }

    /**
     * Kiểm tra yêu cầu nhưng chủ động từ chối trước khi ghi DB: thiết kế gửi OTP
     * còn chờ Luân/Đức duyệt để không vi phạm quy tắc chỉ lưu hash.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID enqueueOtp(String requestKey,String recipient,String code,OffsetDateTime expiresAt) {
        validateRecipient(recipient);
        if (code==null || !code.matches("[0-9]{6}") || expiresAt==null || !expiresAt.toInstant().isAfter(clock.instant())) {
            throw new IllegalArgumentException("OTP must have 6 digits and future expiry");
        }
        throw new IllegalStateException("OTP delivery awaits an approved hash-only design");
    }

    /**
     * Ghi yêu cầu email vé vào cùng transaction của caller, không gửi HTTP ở đây.
     * Caller chịu trách nhiệm quyền gửi/người nhận; nhận 1–20 UUID vé duy nhất.
     * Mỗi lần gọi tạo một row mới, không tự chống trùng yêu cầu từ producer.
     *
     * @return UUID do DB cấp cho yêu cầu gửi, chưa phải xác nhận đã gửi email
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID enqueueTickets(String recipient,List<UUID> ticketIds) {
        validateRecipient(recipient);
        if (ticketIds==null || ticketIds.isEmpty() || ticketIds.size()>20 || ticketIds.stream().anyMatch(java.util.Objects::isNull)
                || ticketIds.stream().distinct().count()!=ticketIds.size()) {
            throw new IllegalArgumentException("Need 1-20 unique ticket ids");
        }
        return outbox.enqueueTickets(recipient,List.copyOf(ticketIds));
    }
    /** Chỉ kiểm tra hình dạng địa chỉ, không xác nhận hộp thư hay quyền sở hữu vé. */
    private static void validateRecipient(String email) {
        if (email==null || email.length()>254 || !email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) {
            throw new IllegalArgumentException("Invalid recipient");
        }
    }
}
