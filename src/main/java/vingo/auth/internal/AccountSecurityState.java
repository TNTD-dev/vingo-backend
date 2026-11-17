package vingo.auth.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Đối chiếu quyền hiện hành để thao tác quản trị vô hiệu JWT cũ ngay lập tức. */
@Component
public class AccountSecurityState implements OAuth2TokenValidator<Jwt> {
    private final JdbcClient jdbc;

    public AccountSecurityState(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long version(UUID userId) {
        return jdbc.sql("SELECT auth_version FROM users WHERE id = :id")
                .param("id", userId).query(Long.class).single();
    }

    public boolean locked(UUID userId) {
        return jdbc.sql("SELECT admin_locked FROM users WHERE id = :id")
                .param("id", userId).query(Boolean.class).single();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Object rawVersion = token.getClaim("av");
            long tokenVersion = rawVersion == null ? 0 : Long.parseLong(rawVersion.toString());
            boolean valid = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM users
                    WHERE id = :id AND status = 'ACTIVE' AND NOT admin_locked
                      AND role = :role AND auth_version = :version)
                    """)
                    .param("id", UUID.fromString(token.getSubject()))
                    .param("role", token.getClaimAsString("role"))
                    .param("version", tokenVersion).query(Boolean.class).single();
            if (valid) return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException ex) {
            // Claim không hợp lệ được trả về như JWT không hợp lệ.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Phiên đăng nhập không còn hợp lệ", null));
    }
}
