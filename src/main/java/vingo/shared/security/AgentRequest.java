package vingo.shared.security;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Ngữ cảnh nội bộ, chỉ filter xác thực được phép thiết lập. */
public final class AgentRequest {
    public static final String SESSION_TOKEN = AgentRequest.class.getName() + ".token";
    static final String VIA_AGENT = AgentRequest.class.getName() + ".viaAgent";
    private AgentRequest() {}
    public static boolean isViaAgent() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && Boolean.TRUE.equals(attributes.getRequest().getAttribute(VIA_AGENT));
    }
}
