package vingo.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginClientTest {
    @Test
    void acceptsOnlyIpLiteralsAndDropsIpv6Zone() {
        assertThat(LoginClient.normalizeIp("192.0.2.10")).isEqualTo("192.0.2.10");
        assertThat(LoginClient.normalizeIp("fe80::1%eth0")).isEqualTo(LoginClient.normalizeIp("fe80::1")).isNotNull();
        for (String invalid : new String[]{null, "", "localhost", "127.1", "192.0.2.1%eth0", "256.2.3.4",
                "1.2.3.4:80", "[::1]", "invalid::ip", "192.0.2.1%a:b", "192.0.2.1/24"}) {
            assertThat(LoginClient.normalizeIp(invalid)).isNull();
        }
    }
}
