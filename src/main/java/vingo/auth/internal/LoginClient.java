package vingo.auth.internal;

import java.net.InetAddress;

/** Thông tin trình duyệt tại thời điểm đăng nhập; cookie chỉ dùng để nhận diện thiết bị. */
public record LoginClient(String agent, String ip, String deviceToken) {
    public LoginClient {
        ip = normalizeIp(ip);
        if (agent != null && agent.codePointCount(0, agent.length()) > 512) {
            agent = agent.substring(0, agent.offsetByCodePoints(0, 512));
        }
    }

    /** Chỉ phân tích IP literal, không tra DNS. PostgreSQL inet không nhận zone của IPv6. */
    public static String normalizeIp(String address) {
        if (address == null || address.isEmpty() || address.startsWith("[")) return null;
        int zone = address.indexOf('%');
        if (zone >= 0) {
            if (zone == address.length() - 1) return null;
            address = address.substring(0, zone);
            if (!address.contains(":")) return null;
        }
        try {
            String literal = InetAddress.ofLiteral(address).getHostAddress();
            // IPv4 phải có đủ bốn octet dạng thập phân.
            return address.contains(":") || literal.equals(address) ? literal : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override public String toString() { return "LoginClient[redacted]"; }
}
