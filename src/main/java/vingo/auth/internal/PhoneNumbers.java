package vingo.auth.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/** Chuẩn hoá số di động Việt Nam dùng chung cho các luồng tài khoản. */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.-]");
    private static final Pattern VIETNAMESE_MOBILE = Pattern.compile("^(\\+84|84|0)(3|5|7|8|9)[0-9]{8}$");

    private PhoneNumbers() {}

    /** Trả số E.164 hoặc rỗng khi đầu vào không phải số di động Việt Nam. */
    public static Optional<String> normalize(String phone) {
        if (phone == null) return Optional.empty();
        String compact = SEPARATORS.matcher(phone).replaceAll("");
        return VIETNAMESE_MOBILE.matcher(compact).matches()
                ? Optional.of("+84" + compact.substring(compact.length() - 9)) : Optional.empty();
    }
}
