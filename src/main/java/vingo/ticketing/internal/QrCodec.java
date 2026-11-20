package vingo.ticketing.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Ký và xác minh mã QR vé dạng {@code VG1.<ticket_id>.<qr_version>.<HMAC>} (không lưu DB). */
@Component
public class QrCodec {

    private static final String PREFIX = "VG1";
    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secretBytes;

    public QrCodec(@Value("${vingo.qr.secret:${QR_SECRET:vingo-dev-qr-secret-change-in-prod}}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("QR_SECRET không được để trống");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record ParsedQr(UUID ticketId, int qrVersion) {}

    public String sign(UUID ticketId, int qrVersion) {
        String payload = PREFIX + "." + ticketId + "." + qrVersion;
        return payload + "." + hmac(payload);
    }

    public Optional<ParsedQr> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        UUID ticketId;
        int version;
        try {
            ticketId = UUID.fromString(parts[1]);
            version = Integer.parseInt(parts[2]);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (version <= 0) {
            return Optional.empty();
        }
        String expected = hmac(PREFIX + "." + ticketId + "." + version);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(new ParsedQr(ticketId, version));
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALG));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Không thể ký QR HMAC", ex);
        }
    }
}
