package vingo.adminops;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.adminops.internal.AuditLogRepository;

/** Ghi đồng bộ từng lần gọi chức năng admin, độc lập với audit thay đổi nghiệp vụ. */
@Service
public class AdminActivityService {
    private final AuditLogRepository audits;
    private final Clock clock;

    public AdminActivityService(AuditLogRepository audits, Clock clock) {
        this.audits = audits;
        this.clock = clock;
    }

    /** Lưu trước khi chạy controller để cả thao tác lỗi cũng có người thực hiện và tên chức năng. */
    @Transactional
    public UUID start(UUID actor, Map<String, Object> details) {
        return audits.insert(actor, "ADMIN_ACTIVITY", "admin_activity", actor, null,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), null, details);
    }

    /** Cập nhật kết quả trên chính bản ghi đã tạo, không sinh thêm dòng. */
    @Transactional
    public void complete(UUID activityId, Map<String, Object> details) {
        audits.completeActivity(activityId, details);
    }
}
