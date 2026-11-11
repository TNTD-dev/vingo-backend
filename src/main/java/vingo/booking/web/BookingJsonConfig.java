package vingo.booking.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vingo.api.model.ListMyOrders200Response;

@Configuration(proxyBeanMethods = false)
public class BookingJsonConfig {

    @Bean
    JsonMapperBuilderCustomizer orderPageJsonCustomizer() {
        // shortcut: chỉ áp cho ListMyOrders200Response; các API phân trang khác (search, admin) cần cùng cách — gộp vào shared khi có trang thứ hai
        return builder -> builder.addMixIn(ListMyOrders200Response.class, OrderPageMixin.class);
    }

    private abstract static class OrderPageMixin {

        // Trang cuối vẫn phải có next_cursor: null dù cấu hình chung bỏ trường null.
        @JsonInclude(JsonInclude.Include.ALWAYS)
        public abstract String getNextCursor();
    }
}
