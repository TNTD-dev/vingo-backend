package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.api.model.AttendeeProfile;
import vingo.api.model.AttendeeRequest;
import vingo.auth.internal.AttendeeProfileRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
public class AttendeeProfileService {
    private static final int MAX_PROFILES = 20;
    private final AttendeeProfileRepository profiles;
    private final Clock clock;

    public AttendeeProfileService(AttendeeProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AttendeeProfile> list(UUID owner) {
        return profiles.list(owner);
    }

    @Transactional(readOnly = true)
    public AttendeeProfile get(UUID owner, UUID id) {
        return profiles.find(owner, id).orElseThrow(AttendeeProfileService::notFound);
    }

    @Transactional
    public AttendeeProfile create(UUID owner, AttendeeRequest request) {
        var details = normalize(request);
        // Cùng khoá với luồng xoá tài khoản. Chỉ đếm sau khi đã giữ khoá.
        if (!profiles.lockLiveOwner(owner)) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Tài khoản đã bị xoá.");
        }
        if (profiles.count(owner) >= MAX_PROFILES) {
            throw new DomainException(ErrorCode.ACTION_STATE_INVALID, "Mỗi tài khoản được lưu tối đa 20 hồ sơ người đi xem.");
        }
        return profiles.insert(owner, details, OffsetDateTime.now(clock));
    }

    @Transactional
    public AttendeeProfile replace(UUID owner, UUID id, AttendeeRequest request) {
        return profiles.replace(owner, id, normalize(request), OffsetDateTime.now(clock))
                .orElseThrow(AttendeeProfileService::notFound);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        if (!profiles.delete(owner, id)) throw notFound();
    }

    private static AttendeeRequest normalize(AttendeeRequest request) {
        String name = request.getFullName() == null ? "" : request.getFullName().strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 120 || name.indexOf('\0') >= 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Họ tên phải gồm từ 1 đến 120 ký tự hợp lệ.");
        }
        String email = request.getEmail() == null ? null : AuthService.normalizeEmail(request.getEmail());
        if (email != null && email.codePointCount(0, email.length()) > 254) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Email vượt quá 254 ký tự.");
        }
        String phone = request.getPhone() == null ? null : AuthService.normalizeAccountPhone(request.getPhone().strip());
        return new AttendeeRequest(name).email(email).phone(phone);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy hồ sơ người đi xem.");
    }
}
