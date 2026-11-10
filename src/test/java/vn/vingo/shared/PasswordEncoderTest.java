package vn.vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import vn.vingo.shared.security.SecurityConfig;

class PasswordEncoderTest {

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void passwordEncoder_usesArgon2id_andMatchesRawPassword() {
        String hash = encoder.encode("Vingo@2026");

        assertThat(hash).startsWith("$argon2id$");
        assertThat(encoder.matches("Vingo@2026", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }
}
