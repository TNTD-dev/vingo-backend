package vingo.adminops.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import vingo.adminops.AdminActivityService;
import vingo.shared.logging.RequestIdFilter;
import vingo.shared.security.CurrentUser;

/** Bao phủ tập trung mọi route admin và soát vé, kể cả chức năng mới thêm sau này. */
@Configuration(proxyBeanMethods = false)
@ControllerAdvice
public class AdminActivityWebConfig implements WebMvcConfigurer, HandlerInterceptor, ResponseBodyAdvice<Object> {
    private static final String ATTRIBUTE = AdminActivityWebConfig.class.getName();
    private final CurrentUser currentUser;
    private final AdminActivityService activities;

    public AdminActivityWebConfig(CurrentUser currentUser, AdminActivityService activities) {
        this.currentUser = currentUser;
        this.activities = activities;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/admin/**", "/api/v1/checkins");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || request.getAttribute(ATTRIBUTE) != null) {
            return true;
        }
        var actor = currentUser.find().filter(user -> user.isAdmin());
        if (actor.isEmpty()) {
            return true;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", method.getMethod().getName());
        details.put("method", request.getMethod());
        details.put("route", String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)));
        details.put("outcome", "STARTED");
        String requestId = response.getHeader(RequestIdFilter.HEADER);
        if (requestId != null) {
            details.put("request_id", requestId);
        }
        Map<String, String> targetIds = new LinkedHashMap<>();
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
            variables.forEach((key, value) -> {
                // Chỉ lưu ID UUID, không lưu URL thực, query, body hoặc token từ client.
                if (value instanceof String text && isUuid(text)) {
                    targetIds.put(key.toString(), text);
                }
            });
        }
        details.put("target_ids", targetIds);
        UUID id = activities.start(actor.orElseThrow().id(), details);
        request.setAttribute(ATTRIBUTE, new Activity(id, details));
        return true;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servletRequest
                && response instanceof ServletServerHttpResponse servletResponse) {
            complete(servletRequest.getServletRequest(), servletResponse.getServletResponse(), body, false);
        }
        return body;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            ModelAndView modelAndView) {
        complete(request, response, null, false);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        complete(request, response, null, ex != null);
    }

    private void complete(HttpServletRequest request, HttpServletResponse response, Object body, boolean failed) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof Activity activity) || activity.completed) {
            return;
        }
        int status = failed ? 500 : response.getStatus();
        activity.details.put("status", status);
        activity.details.put("outcome", status < 400 ? "SUCCEEDED" : "FAILED");
        // Chỉ đọc các trường ID được cho phép; tuyệt đối không serialize toàn bộ response.
        if (body != null) {
            var properties = new BeanWrapperImpl(body);
            Map<String, String> resultIds = new LinkedHashMap<>();
            for (String name : new String[] {"id", "ticketId", "venueId", "eventId", "showtimeId"}) {
                if (properties.isReadableProperty(name) && properties.getPropertyValue(name) instanceof UUID id) {
                    resultIds.put(name, id.toString());
                }
            }
            if (!resultIds.isEmpty()) {
                activity.details.put("result_ids", resultIds);
            }
            for (String name : new String[] {"title", "name", "fullName"}) {
                if (properties.isReadableProperty(name) && properties.getPropertyValue(name) instanceof String label) {
                    activity.details.put("result_label", label.substring(0, Math.min(label.length(), 200)));
                    break;
                }
            }
            // Mã vé công khai giúp truy vết soát vé; không đọc qrToken dùng để xác thực vé.
            if (properties.isReadableProperty("ticketCode")
                    && properties.getPropertyValue("ticketCode") instanceof String ticketCode) {
                activity.details.put("ticket_code", ticketCode);
            }
        }
        activities.complete(activity.id, activity.details);
        activity.completed = true;
    }

    private static boolean isUuid(String text) {
        try {
            return UUID.fromString(text).toString().equalsIgnoreCase(text);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static final class Activity {
        private final UUID id;
        private final Map<String, Object> details;
        private boolean completed;

        private Activity(UUID id, Map<String, Object> details) {
            this.id = id;
            this.details = details;
        }
    }
}
