package vingo.notifications;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vingo.notifications.internal.DeliveryException;
import vingo.notifications.internal.EmailPayload;
import vingo.notifications.internal.EmailSender;
import vingo.notifications.internal.NotificationSettings;
import vingo.notifications.internal.OutboxRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Caller giữ quyền gửi, danh sách vé và transaction nghiệp vụ của mình. */
@Service
public class NotificationsService {
    private static final Logger log = LoggerFactory.getLogger(NotificationsService.class);
    private final OutboxRepository outbox;
    private final EmailSender emailSender;
    private final NotificationSettings settings;
    private final Clock clock;
    public NotificationsService(OutboxRepository outbox, EmailSender emailSender, NotificationSettings settings, Clock clock) {
        this.outbox = outbox;
        this.emailSender = emailSender;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Gửi email OTP trực tiếp ngoài transaction để không lưu OTP dạng rõ vào database.
     *
     * @throws DomainException VALIDATION_ERROR nếu đầu vào không hợp lệ, SERVICE_UNAVAILABLE nếu gửi thất bại
     */
    @Transactional(propagation = Propagation.NEVER)
    public void sendOtpEmail(UUID idempotencyKey, String recipient, String otp, OffsetDateTime expiresAt) {
        validateRecipient(recipient);
        if (idempotencyKey == null || otp == null || !otp.matches("[0-9]{6}")
                || expiresAt == null || !expiresAt.toInstant().isAfter(clock.instant())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "OTP must have 6 digits and future expiry");
        }
        long minutes = Math.max(1, Duration.between(clock.instant(), expiresAt.toInstant()).toMinutes());
        String html = """
                <p>Mã xác thực tài khoản Vingo của bạn là: <strong>%s</strong></p>
                <p>Mã có hiệu lực trong %d phút. Không chia sẻ mã này với bất kỳ ai.</p>
                """.formatted(otp, minutes);
        var payload = new EmailPayload(recipient, "Mã xác thực tài khoản Vingo", html, List.of(), settings.sender());
        try {
            emailSender.send(idempotencyKey, payload);
        } catch (DeliveryException ex) {
            log.atWarn()
                    .addKeyValue("error_code", ex.code())
                    .addKeyValue("retryable", ex.retryable())
                    .log("OTP email delivery failed");
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Không thể gửi email xác thực lúc này");
        }
    }

    /**
     * Kiểm tra yêu cầu nhưng chủ động từ chối trước khi ghi DB: thiết kế gửi OTP
     * còn chờ Luân/Đức duyệt để không vi phạm quy tắc chỉ lưu hash.
     * @throws DomainException VALIDATION_ERROR nếu người nhận, mã hoặc hạn OTP không hợp lệ
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID enqueueOtp(String requestKey,String recipient,String code,OffsetDateTime expiresAt) {
        validateRecipient(recipient);
        if (code==null || !code.matches("[0-9]{6}") || expiresAt==null || !expiresAt.toInstant().isAfter(clock.instant())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,"OTP must have 6 digits and future expiry");
        }
        throw new IllegalStateException("OTP delivery awaits an approved hash-only design");
    }

    /**
     * Ghi yêu cầu email vé vào cùng transaction của caller, không gửi HTTP ở đây.
     * Caller chịu trách nhiệm quyền gửi/người nhận; nhận 1–20 UUID vé duy nhất.
     * Mỗi lần gọi tạo một row mới, không tự chống trùng yêu cầu từ producer.
     *
     * @return UUID do DB cấp cho yêu cầu gửi, chưa phải xác nhận đã gửi email
     * @throws DomainException VALIDATION_ERROR nếu người nhận hoặc danh sách vé không hợp lệ
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID enqueueTickets(String recipient,List<UUID> ticketIds) {
        validateRecipient(recipient);
        if (ticketIds==null || ticketIds.isEmpty() || ticketIds.size()>20 || ticketIds.stream().anyMatch(java.util.Objects::isNull)
                || ticketIds.stream().distinct().count()!=ticketIds.size()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,"Need 1-20 unique ticket ids");
        }
        return outbox.enqueueTickets(recipient,List.copyOf(ticketIds));
    }
    /** Chỉ kiểm tra hình dạng địa chỉ, không xác nhận hộp thư hay quyền sở hữu vé. */
    private static void validateRecipient(String email) {
        if (email==null || email.length()>254 || !email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,"Invalid recipient");
        }
    }
}
