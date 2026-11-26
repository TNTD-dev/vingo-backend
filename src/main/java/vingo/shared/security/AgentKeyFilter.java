package vingo.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** Key dịch vụ bổ sung cho phiên user, không thay thế xác thực phiên. */
public final class AgentKeyFilter extends OncePerRequestFilter {
    private final byte[] key;
    private final ProblemAuthenticationHandlers problems;
    public AgentKeyFilter(String key, ProblemAuthenticationHandlers problems) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var values = request.getHeaders("X-Vingo-Agent-Key");
        if (values.hasMoreElements()) {
            String supplied = values.nextElement();
            if (key.length == 0 || values.hasMoreElements()
                    || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
                problems.commence(request, response, null);
                return;
            }
            request.setAttribute(AgentRequest.VIA_AGENT, true);
        }
        chain.doFilter(request, response);
    }
}
