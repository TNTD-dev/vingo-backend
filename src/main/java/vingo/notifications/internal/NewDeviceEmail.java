package vingo.notifications.internal;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class NewDeviceEmail {
    private static final String SUBJECT = "Đăng nhập Vingo từ thiết bị mới";
    private static final DateTimeFormatter VIETNAM_TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private NewDeviceEmail() {}

    public static EmailPayload render(String recipient, String agent, String ip, OffsetDateTime loginAt, String recoveryUrl) {
        String body = """
                <h1>Đăng nhập từ thiết bị mới</h1>
                <p>Tài khoản Vingo của bạn vừa đăng nhập thành công.</p>
                <p>Thiết bị: <strong>%s</strong><br>Địa chỉ IP: %s<br>Thời gian: %s (giờ Việt Nam)</p>
                <p>Nếu không phải bạn, hãy đặt lại mật khẩu tại <a href="%s">Quên mật khẩu</a>.
                Sau khi bạn đặt lại mật khẩu, Vingo sẽ hủy mọi phiên đăng nhập của tài khoản.</p>
                """.formatted(display(agent), display(ip), VIETNAM_TIME.format(loginAt), EmailLayout.escape(recoveryUrl));
        return new EmailPayload(recipient, SUBJECT, EmailLayout.page(SUBJECT, "Kiểm tra lần đăng nhập mới của bạn.",
                body, "Email tự động, vui lòng không trả lời."), EmailLayout.images());
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "Không xác định" : EmailLayout.escape(value);
    }
}
