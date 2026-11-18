package vingo.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import vingo.auth.internal.sms.SmsDeliveryException;
import vingo.auth.internal.sms.SmsSender;

/** Ghi nhận SMS trong bộ nhớ để test không gọi nhà cung cấp thật. */
public class RecordingSmsSender implements SmsSender {

    private final List<SentSms> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean();

    /** Ghi nhận lần gọi và giả lập lỗi một lần nếu test yêu cầu. */
    @Override
    public void sendOtp(UUID requestId, String phoneE164, String code, int validMinutes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AssertionError("SMS phải được gửi ngoài transaction");
        }
        sent.add(new SentSms(requestId, phoneE164, code, validMinutes));
        if (failNext.getAndSet(false)) {
            throw new SmsDeliveryException("Nhà cung cấp SMS giả lập không khả dụng");
        }
    }

    /** Làm lần gửi kế tiếp thất bại đúng một lần. */
    public void failNext() {
        failNext.set(true);
    }

    /** Lấy bản chụp các lần gọi gửi, kể cả lần nhà cung cấp báo lỗi. */
    public List<SentSms> sent() {
        return List.copyOf(sent);
    }

    /** Lấy mã cuối cùng gửi cho số được chỉ định. */
    public String lastCode(String phone) {
        return sent().reversed().stream().filter(sms -> sms.phone().equals(phone))
                .findFirst().orElseThrow().code();
    }

    /** Xoá dữ liệu giả lập giữa các test. */
    public void reset() {
        sent.clear();
        failNext.set(false);
    }

    /** Thông tin gửi chỉ lưu trong bộ nhớ test, không ghi log. */
    public record SentSms(UUID requestId, String phone, String code, int minutes) {
    }
}
