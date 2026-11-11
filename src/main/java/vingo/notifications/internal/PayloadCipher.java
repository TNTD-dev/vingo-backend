package vingo.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Reader tương thích dữ liệu mã hóa bản cũ, không ghi payload mã hóa mới. */
@Component
public class PayloadCipher {
    private final SecretKeySpec key;
    public PayloadCipher(NotificationSettings settings) {
        key = legacyKey(settings.encryptionKey());
    }
    /**
     * Khóa legacy hỏng được coi là chưa có: không chặn khởi động hoặc email mới.
     * Thư cũ vẫn được pause bởi requireKey, chờ cấu hình đúng; không log secret.
     */
    private static SecretKeySpec legacyKey(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            return raw.length == 32 ? new SecretKeySpec(raw, "AES") : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
    /** Giải mã thư cũ bằng AES-GCM; thiếu/sai khóa hoặc dữ liệu hỏng không được xoá thư. */
    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new PayloadUnavailableException();
        }
    }

    private void requireKey() {
        if (key == null) throw new PayloadUnavailableException();
    }
}
