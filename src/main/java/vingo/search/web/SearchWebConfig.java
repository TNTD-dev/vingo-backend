package vingo.search.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Gắn giới hạn tần suất theo IP cho các endpoint search. */
@Configuration(proxyBeanMethods = false)
class SearchWebConfig implements WebMvcConfigurer {

    private final SearchRateLimitInterceptor rateLimit;

    SearchWebConfig(SearchRateLimitInterceptor rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit);
    }
}
