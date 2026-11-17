package vingo.notifications.internal;

import static vingo.notifications.internal.EmailLayout.ACCENT;
import static vingo.notifications.internal.EmailLayout.ACCENT_SOFT;
import static vingo.notifications.internal.EmailLayout.INK;
import static vingo.notifications.internal.EmailLayout.MONO;
import static vingo.notifications.internal.EmailLayout.MUTED;

import java.util.List;

/**
 * Dựng email mã OTP đăng ký. Mã chỉ nằm trong thân thư, không ở tiêu đề hay dòng xem trước
 * để không lộ trên thông báo màn hình khoá.
 */
public final class OtpEmailRenderer {
    public static final String SUBJECT = "Mã xác thực tài khoản Vingo";

    private OtpEmailRenderer() {}

    /** Caller đã kiểm OTP đúng 6 chữ số; minutes là số phút còn hiệu lực, tối thiểu 1. */
    public static EmailPayload render(String recipient, String otp, long minutes, String sender) {
        String body = """
                %1$s
                <h1 style="margin:0 0 10px 0;font-size:28px;line-height:1.2;font-weight:700;letter-spacing:-0.5px;color:%2$s;">Mã xác thực tài khoản</h1>
                <p style="margin:0 0 24px 0;font-size:16px;line-height:1.6;color:%3$s;">Chào bạn, nhập mã dưới đây trên Vingo để hoàn tất đăng ký tài khoản.</p>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0">
                <tr><td align="center" style="background:%2$s;border-radius:20px;padding:28px 16px;">
                <div style="font-family:%4$s;font-size:12px;line-height:16px;letter-spacing:1.5px;text-transform:uppercase;color:#b3b3b3;">Mã OTP của bạn</div>
                <div style="font-family:%4$s;font-size:40px;line-height:48px;font-weight:600;letter-spacing:12px;color:%5$s;padding:12px 0 12px 12px;"><strong>%6$s</strong></div>
                <div style="font-size:14px;line-height:20px;color:#cccccc;">Hết hạn sau <span style="color:#ffffff;font-weight:600;">%7$d phút</span></div>
                </td></tr>
                </table>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin-top:24px;">
                <tr><td style="background:%8$s;border-radius:16px;padding:16px 18px;font-size:14px;line-height:1.55;color:%2$s;">
                Đừng chia sẻ mã này với ai, kể cả người tự nhận là nhân viên Vingo. Nếu bạn không đăng ký tài khoản, cứ bỏ qua email này.
                </td></tr>
                </table>
                """.formatted(EmailLayout.Image.MASCOT_WAVE.tag("margin:0 0 24px 0;"), INK, MUTED, MONO, ACCENT,
                        EmailLayout.escape(otp), minutes, ACCENT_SOFT);
        String html = EmailLayout.page(SUBJECT, "Mã có hiệu lực trong %d phút. Không chia sẻ mã cho ai.".formatted(minutes),
                body, "Email tự động, vui lòng không trả lời.");
        return new EmailPayload(recipient, SUBJECT, html,
                List.copyOf(EmailLayout.images(EmailLayout.Image.MASCOT_WAVE)), sender);
    }
}
