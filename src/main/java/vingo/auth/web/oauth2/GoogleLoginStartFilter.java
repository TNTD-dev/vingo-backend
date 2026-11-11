package vingo.auth.web.oauth2;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;
import vingo.auth.internal.GoogleLoginRateLimiter;
import vingo.shared.error.ErrorCode;
import vingo.shared.web.GlobalExceptionHandler;

/** Kiểm tra Redis trước khi Spring lưu yêu cầu OAuth2 vào PostgreSQL. */
final class GoogleLoginStartFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GoogleLoginStartFilter.class);

    private final GoogleLoginRateLimiter limiter;
    private final JsonMapper json;

    /** Nhận bộ đếm Redis và mapper dùng cho phản hồi ProblemDetail. */
    GoogleLoginStartFilter(GoogleLoginRateLimiter limiter, JsonMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    /** Chỉ tính request GET bắt đầu đăng nhập. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GoogleOAuth2SecurityConfig.START_REQUEST.matches(request);
    }

    /** Chặn request trước khi tạo trạng thái OAuth2 nếu hết lượt hoặc Redis gặp sự cố. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfter;
        try {
            retryAfter = limiter.retryAfter(request.getRemoteAddr());
        } catch (DataAccessException ex) {
            log.warn("Google OAuth2 start rejected: reason=rate_limiter_unavailable");
            writeProblem(response, ErrorCode.SERVICE_UNAVAILABLE, "Tạm thời không thể bắt đầu đăng nhập Google");
            return;
        }
        if (retryAfter > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            writeProblem(response, ErrorCode.RATE_LIMITED, "Thử lại sau " + retryAfter + " giây");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Trả lỗi theo cùng định dạng ProblemDetail với các endpoint API. */
    private void writeProblem(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(code, detail));
    }
}
