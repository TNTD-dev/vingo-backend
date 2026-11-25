package vingo.support;

import java.net.HttpCookie;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import vingo.auth.internal.TokenSecrets;
import vingo.shared.security.Role;

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

    /** Tạo user trong DB và trả token phiên hợp lệ của user đó. */
    protected String tokenFor(Role role) {
        UUID userId = jdbc.sql("""
                        INSERT INTO user_account (email, full_name, password_hash, role, email_verified_at, phone)
                        VALUES (:email, 'Test User', 'x', :role::user_role, :now, :phone) RETURNING id
                        """)
                .param("email", UUID.randomUUID() + "@test.vingo")
                .param("role", role.name())
                .param("phone", CatalogFixture.uniquePhone())
                .param("now", java.time.OffsetDateTime.now(clock))
                .query(UUID.class)
                .single();
        return tokenFor(userId, role);
    }

    protected String tokenFor(UUID userId, Role role) {
        // Fixtures remain valid when a scenario advances or resets the business clock.
        String token = TokenSecrets.generate();
        var now = catalog.now();
        jdbc.sql("""
                INSERT INTO login_session (user_id, token_hash, created_at, last_seen_at, expires_at)
                VALUES (:id, :hash, :now, :now, :expires)
                """).param("id", userId).param("hash", TokenSecrets.hash(token))
                .param("now", now).param("expires", now.plusYears(100)).update();
        return token;
    }

    protected static String sessionToken(HttpHeaders headers) {
        return headers.getOrEmpty(HttpHeaders.SET_COOKIE).stream().flatMap(line -> HttpCookie.parse(line).stream())
                .filter(cookie -> cookie.getName().equals("vingo_session") || cookie.getName().equals("__Host-vingo_session"))
                .findFirst().orElseThrow().getValue();
    }
}
