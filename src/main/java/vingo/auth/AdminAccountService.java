package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.api.model.AdminAccount;
import vingo.api.model.AdminAccountPage;
import vingo.api.model.AdminAccountUpdate;
import vingo.auth.internal.AdminAccountRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminAccountService.class);
    private final AdminAccountRepository accounts;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AdminAccountService(AdminAccountRepository accounts, CurrentUser currentUser, Clock clock) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminAccountPage list(String q, String role, String status, String cursor, Integer limit) {
        return list(q,role,status,cursor,limit,"newest");
    }

    @Transactional(readOnly = true)
    public AdminAccountPage list(String q, String role, String status, String cursor, Integer limit, String sort) {
        sort = sort == null ? "newest" : sort;
        if (!List.of("newest","oldest","name").contains(sort)) throw invalid("Thứ tự không hợp lệ");
        if (cursor != null && cursor.contains(":")) {
            var parts=cursor.split(":",2);
            if (!parts[0].equals(sort)) throw invalid("Cursor không khớp thứ tự");
            cursor=parts[1];
        } else if (cursor != null && !"newest".equals(sort)) throw invalid("Cursor không khớp thứ tự");
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100 || (q != null && q.length() > 200)
                || (role != null && !List.of("BUYER", "ADMIN").contains(role))
                || (status != null && !List.of("ACTIVE", "LOCKED", "UNVERIFIED", "DELETED").contains(status))) {
            throw invalid("Bộ lọc không hợp lệ");
        }
        UUID after;
        try { after = cursor == null ? null : UUID.fromString(cursor); }
        catch (IllegalArgumentException ex) { throw invalid("Cursor không hợp lệ"); }
        var rows = accounts.listSorted(q == null || q.isBlank() ? null : q.strip(), role, status, after, size + 1, sort);
        boolean more = rows.size() > size;
        var data = more ? rows.subList(0, size) : rows;
        return new AdminAccountPage().data(data).nextCursor(more ? ("newest".equals(sort) ? "" : sort+":") + data.getLast().getId() : null);
    }

    @Transactional(readOnly = true)
    public AdminAccount get(UUID id) {
        return accounts.find(id, false).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy tài khoản"));
    }

    @Transactional
    public AdminAccount update(UUID id, AdminAccountUpdate body) {
        if (body == null || body.getReason() == null || body.getReason().isBlank() || body.getReason().length() > 500
                || (body.getRole() == null && body.getLocked() == null)) throw invalid("Cần thao tác và lý do hợp lệ");
        accounts.lockAdministration();
        UUID actor = currentUser.require().id();
        var administrator = accounts.find(actor, false).orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "Phiên không hợp lệ"));
        if (administrator.getRole() != AdminAccount.RoleEnum.ADMIN || administrator.getStatus() != AdminAccount.StatusEnum.ACTIVE
                || administrator.getAdminLocked()) throw new DomainException(ErrorCode.FORBIDDEN, "Không còn quyền quản trị");
        var before = accounts.find(id, true).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy tài khoản"));
        if (!List.of(AdminAccount.StatusEnum.ACTIVE, AdminAccount.StatusEnum.LOCKED).contains(before.getStatus())) {
            throw invalid("Chỉ quản lý quyền/khóa của tài khoản đã xác minh và chưa xóa");
        }
        String role = body.getRole() == null ? before.getRole().getValue() : body.getRole().getValue();
        boolean locked = body.getLocked() == null ? before.getAdminLocked() : body.getLocked();
        String status = body.getLocked() == null ? before.getStatus().getValue() : (locked ? "LOCKED" : "ACTIVE");
        if ("ADMIN".equals(role) && !before.getHasPassword()) throw invalid("Tài khoản cần thiết lập mật khẩu trước khi được cấp ADMIN");
        if (actor.equals(id) && (!"ADMIN".equals(role) || locked)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Không được tự hạ quyền hoặc tự khóa tài khoản");
        }
        if (role.equals(before.getRole().getValue()) && status.equals(before.getStatus().getValue())
                && locked == before.getAdminLocked()) return before;
        if (before.getRole() == AdminAccount.RoleEnum.ADMIN && before.getStatus() == AdminAccount.StatusEnum.ACTIVE
                && (!"ADMIN".equals(role) || !"ACTIVE".equals(status)) && accounts.activeAdmins() <= 1) {
            throw new DomainException(ErrorCode.ACTION_STATE_INVALID, "Cần giữ ít nhất một ADMIN hoạt động");
        }
        var now = OffsetDateTime.now(clock);
        accounts.update(id, role, status, locked, body.getLocked() != null, now);
        var after = get(id);
        accounts.audit(actor, before, after, body.getReason().strip(), now);
        log.atInfo().addKeyValue("actor_id", actor).addKeyValue("user_id", id)
                .addKeyValue("role", role).addKeyValue("status", status).log("Updated account access");
        return after;
    }

    @Transactional(readOnly = true)
    public vingo.api.model.AdminUserStatistics statistics() { return accounts.statistics(); }

    @Transactional(readOnly = true)
    public vingo.api.model.AdminLoginHistory loginHistory(UUID user, String cursor, Integer limit) {
        get(user);
        int size=limit==null?20:limit;
        if(size<1||size>100) throw invalid("limit từ 1 đến 100");
        UUID after;
        try { after=cursor==null?null:UUID.fromString(cursor); }
        catch(IllegalArgumentException ex) { throw invalid("Cursor không hợp lệ"); }
        var rows=accounts.loginHistory(user,after,size+1);
        boolean more=rows.size()>size;
        var page=more?rows.subList(0,size):rows;
        return new vingo.api.model.AdminLoginHistory().data(page)
                .nextCursor(more?page.getLast().getId().toString():null);
    }

    @Transactional(readOnly = true)
    public String export(String q, String role, String status) {
        // Dùng cùng validation/bộ lọc danh sách trước khi đọc dữ liệu xuất.
        list(q,role,status,null,1);
        var rows=accounts.listSorted(q==null||q.isBlank()?null:q.strip(),role,status,null,10001,"newest");
        if(rows.size()>10000) throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,"Thu hẹp bộ lọc để xuất tối đa 10000 tài khoản");
        var csv=new StringBuilder("\uFEFFid,full_name,email,phone,role,status,created_at\r\n");
        for(var row:rows) {
            csv.append(java.util.stream.Stream.of(row.getId(),row.getFullName(),row.getEmail(),row.getPhone(),
                    row.getRole().getValue(),row.getStatus().getValue(),row.getCreatedAt())
                    .map(AdminAccountService::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvCell(Object value) {
        String text=value==null?"":value.toString();
        if(!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0))>=0) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }

    private static DomainException invalid(String message) { return new DomainException(ErrorCode.VALIDATION_ERROR, message); }
}
