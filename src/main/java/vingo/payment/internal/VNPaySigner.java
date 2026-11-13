package vingo.payment.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Tạo và xác minh chữ ký HMAC-SHA512 cho URL thanh toán, IPN và querydr của VNPay. */
@Component
public class VNPaySigner {

    private static final String HMAC_SHA512 = "HmacSHA512";

    private final byte[] secretBytes;

    public VNPaySigner(@Value("${vingo.vnpay.hash-secret}") String hashSecret) {
        this.secretBytes = (hashSecret == null ? "" : hashSecret).getBytes(StandardCharsets.UTF_8);
    }

    /** Sắp xếp các tham số vnp_* theo thứ tự từ điển, URL-encode US_ASCII và ký HMAC-SHA512. */
    public String signParams(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("vnp_SecureHash");
        sorted.remove("vnp_SecureHashType");

        String canonical = sorted.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().startsWith("vnp_")
                        && e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));

        return hmacSha512(canonical);
    }

    /** Xác minh vnp_SecureHash trên toàn bộ tham số callback từ VNPay bằng so sánh thời gian cố định. */
    public boolean verifyParams(Map<String, String> params) {
        if (params == null) {
            return false;
        }
        String receivedHash = params.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            return false;
        }
        String expectedHash = signParams(params);
        byte[] expectedBytes = expectedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] receivedBytes = receivedHash.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedBytes, receivedBytes);
    }

    /** Ký trực tiếp một chuỗi dữ liệu bằng HMAC-SHA512 (dùng cho cả query string và querydr). */
    public String hmacSha512(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(new SecretKeySpec(secretBytes, HMAC_SHA512));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Không thể tạo chữ ký HMAC-SHA512", ex);
        }
    }
}