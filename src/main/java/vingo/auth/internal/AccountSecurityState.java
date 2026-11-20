package vingo.auth.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
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
    private final Clock clock;

    public AccountSecurityState(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long version(UUID userId) {
        return jdbc.sql("SELECT auth_version FROM user_account WHERE id = :id")
                .param("id", userId).query(Long.class).single();
    }

    public boolean locked(UUID userId) {
        return jdbc.sql("SELECT admin_locked FROM user_account WHERE id = :id")
                .param("id", userId).query(Boolean.class).single();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Object rawVersion = token.getClaim("av");
            long tokenVersion = rawVersion == null ? 0 : Long.parseLong(rawVersion.toString());
            boolean valid = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM user_account
                    WHERE id = :id AND deleted_at IS NULL AND email_verified_at IS NOT NULL AND NOT admin_locked
                      AND (locked_until IS NULL OR locked_until <= :now)
                      AND role = CAST(:role AS user_role) AND auth_version = :version)
                    """)
                    .param("now", OffsetDateTime.now(clock))
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
