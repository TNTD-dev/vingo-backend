package vingo.shared.config;

import javax.sql.DataSource;

import com.p6spy.engine.spy.P6DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bọc DataSource bằng P6Spy để sentry-jdbc ghi span cho từng câu SQL trong trace.
 *
 * <p>Bọc ở bean thay vì đổi JDBC URL sang {@code jdbc:p6spy:...}: không phải sửa {@code DB_URL}
 * trên server hay cấu hình Testcontainers. Span chỉ chứa SQL có dấu {@code ?}, không chứa giá trị tham số.
 */
@Configuration(proxyBeanMethods = false)
class SentryJdbcConfig {

    @Bean
    static BeanPostProcessor sentryJdbcDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof P6DataSource)) {
                    return new P6DataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
