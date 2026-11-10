package vingo.shared.web;

import java.util.Arrays;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gắn base path {@code /api/v1} cho mọi controller implements interface sinh từ contract ({@code vingo.api}).
 *
 * <p>Đường dẫn trong yaml không có {@code /api/v1} (nằm ở {@code servers}), nên controller không tự thêm prefix.
 */
@Configuration(proxyBeanMethods = false)
public class ApiPathConfig implements WebMvcConfigurer {

    public static final String BASE_PATH = "/api/v1";
    private static final String CONTRACT_PACKAGE = "vingo.api";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(BASE_PATH, ApiPathConfig::implementsContract);
    }

    static boolean implementsContract(Class<?> type) {
        return Arrays.stream(type.getInterfaces())
                .anyMatch(i -> i.getPackageName().equals(CONTRACT_PACKAGE));
    }
}
