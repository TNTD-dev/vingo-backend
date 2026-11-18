package vingo.auth.internal.sms;

/** Che số điện thoại trước khi ghi log: giữ đầu số và ẩn 3 chữ số cuối. */
public final class PhoneMask {

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() <= 3) {
            return "***";
        }
        return phone.substring(0, phone.length() - 3) + "***";
    }
}
