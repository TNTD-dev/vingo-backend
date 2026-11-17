package vingo.notifications.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Khung HTML chung cho email Vingo (logo, thẻ trắng, chân trang). Dựng bằng bảng lồng nhau
 * và style inline vì Gmail/Outlook bỏ thẻ style và không hỗ trợ flex. Ảnh là PNG nhúng qua
 * cid, không dùng SVG hay URL ngoài để thư hiển thị được cả khi chưa có domain web.
 */
public final class EmailLayout {
    public static final String INK = "#1a1a1a";
    public static final String ACCENT = "#c6f24e";
    public static final String ACCENT_SOFT = "#f1fbd3";
    public static final String MUTED = "#4d4d4d";
    public static final String SANS = "Inter,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    public static final String MONO = "'Geist Mono',SFMono-Regular,Menlo,Consolas,monospace";
    private static final String BRAND = "Fredoka,Inter,-apple-system,'Segoe UI',Roboto,Arial,sans-serif";

    /** Ảnh thương hiệu nằm trong classpath, đọc một lần khi nạp lớp. */
    public enum Image {
        LOGO("logo.png", 24, 30),
        MASCOT_WAVE("mascot-wave.png", 92, 88),
        MASCOT_CELEBRATE("mascot-celebrate.png", 92, 88);

        private final String file;
        private final int width;
        private final int height;
        private final String base64;

        Image(String file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
            try (var in = EmailLayout.class.getResourceAsStream("/notifications/email/" + file)) {
                if (in == null) throw new IllegalStateException("Missing email image " + file);
                this.base64 = Base64.getEncoder().encodeToString(in.readAllBytes());
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        public String cid() { return "vingo-" + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

        /** Thẻ img trỏ tới ảnh nhúng, kích thước cố định để Outlook không phóng ảnh 2x. */
        public String tag(String extraStyle) {
            return "<img src=\"cid:%s\" width=\"%d\" height=\"%d\" alt=\"\" style=\"display:block;border:0;outline:none;%s\">"
                    .formatted(cid(), width, height, extraStyle);
        }

        EmailPayload.Attachment attachment() {
            return new EmailPayload.Attachment(file, base64, "image/png", cid());
        }
    }

    private EmailLayout() {}

    /** Đính kèm các ảnh mà phần thân thư dùng; logo luôn có vì nằm ở đầu khung. */
    public static List<EmailPayload.Attachment> images(Image... used) {
        var all = new java.util.LinkedHashSet<Image>();
        all.add(Image.LOGO);
        all.addAll(Arrays.asList(used));
        return all.stream().map(Image::attachment).toList();
    }

    /**
     * Bọc phần thân đã escape vào khung chung.
     *
     * @param title tiêu đề tài liệu, cũng là tên tab khi mở thư trên trình duyệt
     * @param preheader dòng xem trước trong hộp thư, không chứa dữ liệu nhạy cảm như OTP
     * @param body HTML phần thân, caller chịu trách nhiệm escape dữ liệu động
     * @param footerNote dòng nhỏ cuối thư
     */
    public static String page(String title, String preheader, String body, String footerNote) {
        return """
                <!doctype html>
                <html lang="vi">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light">
                <meta name="supported-color-schemes" content="light">
                <title>%1$s</title>
                </head>
                <body style="margin:0;padding:0;background:#f0f0f0;">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:#f0f0f0;">%2$s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:#f0f0f0;">
                <tr><td align="center" style="padding:32px 12px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:600px;">
                <tr><td style="padding:0 4px 20px 4px;">
                <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                <td style="vertical-align:middle;">%3$s</td>
                <td style="vertical-align:middle;padding-left:8px;font-family:%4$s;font-size:26px;line-height:30px;font-weight:600;letter-spacing:-0.5px;color:%5$s;">vingo</td>
                </tr></table>
                </td></tr>
                <tr><td style="background:#ffffff;border-radius:24px;padding:36px 28px;font-family:%6$s;color:%5$s;">
                %7$s
                </td></tr>
                <tr><td align="center" style="padding:24px 16px 0 16px;font-family:%6$s;font-size:13px;line-height:1.6;color:#595959;">
                Vingo · Đặt vé sự kiện qua chat<br>
                <span style="font-size:12px;">%8$s</span>
                </td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(escape(title), escape(preheader), Image.LOGO.tag(""), BRAND, INK, SANS, body, escape(footerNote));
    }

    /** Huy hiệu bo tròn (Thanh toán thành công, Hợp lệ...). */
    public static String pill(String text, String background) {
        return "<span style=\"display:inline-block;background:%s;color:%s;border-radius:999px;padding:4px 12px;font-family:%s;font-size:12px;line-height:16px;font-weight:600;white-space:nowrap;\">%s</span>"
                .formatted(background, INK, SANS, escape(text));
    }

    /** Mã hoá ký tự HTML để dữ liệu động không trở thành markup thực thi. */
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
