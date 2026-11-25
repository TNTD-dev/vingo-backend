package vingo.shared.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Component
public class CurrentUser {

    public Optional<UUID> sessionId() {
        return find().map(AuthenticatedUser::sessionId);
    }

    public AuthenticatedUser require() {
        return find().orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "Cần đăng nhập"));
    }

    public Optional<AuthenticatedUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser user
                ? Optional.of(user) : Optional.empty();
    }

    /** Buyer chỉ thao tác trên dữ liệu của mình. Không đúng chủ thì trả 404 để không lộ dữ liệu người khác. */
    public void requireOwner(UUID ownerId) {
        AuthenticatedUser user = require();
        if (!user.id().equals(ownerId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy");
        }
    }
}
