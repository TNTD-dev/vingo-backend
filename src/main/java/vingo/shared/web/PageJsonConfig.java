package vingo.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/** Mọi model sinh có {@code getNextCursor()} luôn trả {@code next_cursor}, kể cả null; áp cả search, trang cuối trả {@code "next_cursor": null} thay vì bỏ trường. */
@Configuration(proxyBeanMethods = false)
public class PageJsonConfig {

    @Bean
    JsonMapperBuilderCustomizer pageJsonCustomizer() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadata, factory) -> true);
        var models = scanner.findCandidateComponents("vingo.api.model").stream()
                .map(candidate -> ClassUtils.resolveClassName(candidate.getBeanClassName(),
                        PageJsonConfig.class.getClassLoader()))
                .filter(model -> ReflectionUtils.findMethod(model, "getNextCursor") != null)
                .toList();
        return builder -> models.forEach(model -> builder.addMixIn(model, PageMixin.class));
    }

    private abstract static class PageMixin {

        // Trang cuối vẫn trả next_cursor theo cấu trúc phân trang chung.
        @JsonInclude(JsonInclude.Include.ALWAYS)
        public abstract String getNextCursor();
    }
}
