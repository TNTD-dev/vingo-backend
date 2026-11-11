package vingo.shared.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/**
 * Lấy người dùng hiện tại. Không đọc header Authorization bằng tay.
 *
 * <p>Access token: {@code sub} = user id, claim {@code role} = BUYER | ADMIN.
 */
@Component
public class CurrentUser {

    public static final String ROLE_CLAIM = "role";
    public static final String SESSION_CLAIM = "sid";

    /** Lấy UUID phiên từ claim sid khi request mang JWT của một phiên Vingo. */
    public Optional<UUID> sessionId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(jwt.getClaimAsString(SESSION_CLAIM)).map(UUID::fromString);
        }
        return Optional.empty();
    }

    public AuthenticatedUser require() {
        return find().orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "Cần đăng nhập"));
    }

    public Optional<AuthenticatedUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(ROLE_CLAIM))));
        }
        return Optional.empty();
    }

    /** Buyer chỉ thao tác trên dữ liệu của mình. Không đúng chủ thì trả 404 để không lộ dữ liệu người khác. */
    public void requireOwner(UUID ownerId) {
        AuthenticatedUser user = require();
        if (!user.id().equals(ownerId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy");
        }
    }
}
