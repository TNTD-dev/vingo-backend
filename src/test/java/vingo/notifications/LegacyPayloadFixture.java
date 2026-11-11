package vingo.notifications;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Chỉ sinh dữ liệu tương thích bản cũ trong DB test tạm. */
public final class LegacyPayloadFixture {
    private LegacyPayloadFixture() {}
    public static String encrypt(String text) {
        try {
            byte[] nonce=new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(new byte[32],"AES"),new GCMParameterSpec(128,nonce));
            byte[] ciphertext=cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] bytes=java.util.Arrays.copyOf(nonce,nonce.length+ciphertext.length);
            System.arraycopy(ciphertext,0,bytes,nonce.length,ciphertext.length);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception exception) { throw new AssertionError("Cannot create legacy test fixture",exception); }
    }
}
