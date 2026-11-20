package vingo.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 18 và Redis thật cho test integration. Không dùng H2.
 *
 * <p>Container là {@code static}, khởi động một lần cho cả JVM test và dùng chung cho mọi Spring context
 * (api, worker, context có property riêng). Không khai báo container là bean: nếu là bean thì mỗi context
 * tự dựng một Postgres + Redis + chạy lại Flyway, và context bị đóng ({@code @DirtiesContext}) sẽ tắt luôn
 * container. Ryuk của Testcontainers dọn container khi JVM kết thúc.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    // Mọi context đang được cache cùng giữ pool kết nối tới DB này (mỗi pool tối đa 10),
    // nên nâng giới hạn mặc định 100 kết nối của Postgres.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withCommand("postgres", "-c", "max_connections=500");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
        // Migrate một lần ngay khi DB lên: context worker (profile worker tắt Flyway) chạy trước hay sau
        // context api đều thấy schema đầy đủ. Context api vẫn chạy Flyway nhưng không còn gì để áp.
        Flyway.configure()
                .placeholders(java.util.Map.of("allowDestructiveReset", "true"))
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    /** Container Postgres dùng chung, cho test cần kết nối riêng ngoài pool của ứng dụng (ví dụ test migration). */
    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    DynamicPropertyRegistrar testcontainersProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.url",
                    () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        };
    }
}
