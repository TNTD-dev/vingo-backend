package vingo.ticketing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class QrCodecTest {

    @Test
    void constructor_throwsWhenSecretIsMissingOrBlank() {
        assertThatThrownBy(() -> new QrCodec(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QR_SECRET");
        assertThatThrownBy(() -> new QrCodec(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QR_SECRET");
        assertThatThrownBy(() -> new QrCodec("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QR_SECRET");
    }

    @Test
    void signAndVerify_roundTripsWithValidSecret() {
        QrCodec codec = new QrCodec("unit-test-qr-secret-at-least-32-bytes");
        UUID ticketId = UUID.randomUUID();
        String token = codec.sign(ticketId, 1);

        assertThat(token).startsWith("VG1." + ticketId + ".1.");
        assertThat(codec.verify(token)).contains(new QrCodec.ParsedQr(ticketId, 1));
    }
}
