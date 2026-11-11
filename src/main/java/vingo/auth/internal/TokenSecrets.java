package vingo.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class TokenSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Các thao tác sinh và băm token không cần instance. */
    private TokenSecrets() {
    }

    /** Sinh 32 byte ngẫu nhiên, mã hóa Base64 URL không padding. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Tạo giá trị SHA-256 dạng hex để đối chiếu token trong kho lưu trữ. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
