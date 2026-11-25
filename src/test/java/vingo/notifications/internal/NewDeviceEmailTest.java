package vingo.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class NewDeviceEmailTest {
    @Test
    void rendersEscapedDeviceVietnamTimeAndPlainRecoveryPage() {
        var email = NewDeviceEmail.render("buyer@example.test", "Browser <tag> & \"mobile\"", "192.0.2.5",
                OffsetDateTime.parse("2026-11-25T18:04:05Z"), "https://web.example/forgot-password");
        assertThat(email.html()).contains("Browser &lt;tag&gt; &amp; &quot;mobile&quot;", "192.0.2.5",
                "01:04:05 26/11/2026", "giờ Việt Nam", "Quên mật khẩu", "mọi phiên",
                "href=\"https://web.example/forgot-password\"", "cid:vingo-logo").doesNotContain("<tag>");
        assertThat(email.attachments()).extracting(EmailPayload.Attachment::contentId).containsExactly("vingo-logo");
    }
}
