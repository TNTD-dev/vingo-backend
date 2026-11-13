package vingo.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import vingo.shared.security.CurrentUser;
import vingo.shared.security.JwtProperties;
import vingo.shared.security.Role;
import vingo.shared.security.SecurityConfig;

/**
 * Base class cho test integration: PostgreSQL 18 thật (Testcontainers), Flyway đã chạy, client gọi API.
 *
 * <p>Container dùng chung cho cả lần chạy test. Mỗi test tự tạo dữ liệu mình cần.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "test"})
@Import({TestcontainersConfig.class, TestClockConfig.class, IntegrationTest.ApiClientConfig.class})
public abstract class IntegrationTest {

    /** Chỉ API cần client HTTP; worker vẫn dùng chung DB/Testcontainers của base. */
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("api")
    @AutoConfigureRestTestClient
    static class ApiClientConfig {}

    // Worker kế thừa base này không có HTTP client; API tests vẫn dùng client thật.
    @Autowired(required = false)
    protected RestTestClient client;

    @Autowired
    protected JdbcClient jdbc;

    /** Clock của ứng dụng trong test. Mặc định theo giờ thật; test cần mốc cố định thì gọi {@code set}. */
    @Autowired
    protected MutableClock clock;

    @Autowired
    private JwtProperties jwtProperties;

    /** Dựng sân, sự kiện, suất, tồn kho cho test booking. */
    protected CatalogFixture catalog;

    @Autowired
    private StringRedisTemplate redis;

    /** Xoá sạch Redis trước mỗi test để cache và bộ đếm rate limit của test trước không ảnh hưởng test sau. */
    @BeforeEach
    void flushRedis() {
        try {
            redis.execute((RedisCallback<Object>) connection -> {
                connection.serverCommands().flushAll();
                return null;
            });
        } catch (RuntimeException ex) {
            // Test cố ý cho Redis sập: không có gì để xoá.
        }
    }

    @BeforeEach
    void initializeCatalogFixture() {
        catalog = new CatalogFixture(jdbc, clock);
    }

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
