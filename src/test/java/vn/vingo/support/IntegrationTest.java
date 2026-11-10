package vn.vingo.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import vn.vingo.shared.security.CurrentUser;
import vn.vingo.shared.security.JwtProperties;
import vn.vingo.shared.security.Role;
import vn.vingo.shared.security.SecurityConfig;

/**
 * Base class cho test integration: PostgreSQL 18 thật (Testcontainers), Flyway đã chạy, client gọi API.
 *
 * <p>Container dùng chung cho cả lần chạy test. Mỗi test tự tạo dữ liệu mình cần.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles({"api", "test"})
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private JwtProperties jwtProperties;

    /** Tạo user trong DB và trả access token hợp lệ của user đó. */
    protected String tokenFor(Role role) {
        UUID userId = jdbc.sql("""
                        INSERT INTO users (email, full_name, password_hash, role, status)
                        VALUES (:email, 'Test User', 'x', :role, 'ACTIVE') RETURNING id
                        """)
                .param("email", UUID.randomUUID() + "@test.vingo")
                .param("role", role.name())
                .query(UUID.class)
                .single();
        return tokenFor(userId, role);
    }

    protected String tokenFor(UUID userId, Role role) {
        var encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(jwtProperties)).build();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(userId.toString())
                .claim(CurrentUser.ROLE_CLAIM, role.name())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
