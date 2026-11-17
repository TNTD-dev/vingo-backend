package vingo.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OtpEmailRendererTest {

    @Test
    void render_putsOtpOnlyInBody_andEmbedsBrandImages() {
        var payload = OtpEmailRenderer.render("buyer@example.test", "482913", 5, "Vingo <no-reply@vingo.id.vn>");

        assertThat(payload.subject()).isEqualTo("Mã xác thực tài khoản Vingo").doesNotContain("482913");
        assertThat(payload.html()).contains("<strong>482913</strong>", "Hết hạn sau", "5 phút",
                "cid:vingo-logo", "cid:vingo-mascot-wave");
        // Dòng xem trước hiện trên thông báo, không được chứa mã.
        var preheader = payload.html().substring(payload.html().indexOf("display:none"));
        assertThat(preheader.substring(0, preheader.indexOf("</div>"))).doesNotContain("482913");
        assertThat(payload.attachments()).extracting(EmailPayload.Attachment::contentId)
                .containsExactly("vingo-logo", "vingo-mascot-wave");
        assertThat(payload.attachments()).allSatisfy(a -> {
            assertThat(a.contentType()).isEqualTo("image/png");
            assertThat(a.content()).isNotBlank();
        });
        assertThat(payload.sender()).isEqualTo("Vingo <no-reply@vingo.id.vn>");
    }

    @Test
    void render_usesTablesWithoutExternalImagesOrSvg() {
        var html = OtpEmailRenderer.render("buyer@example.test", "000001", 1, null).html();

        assertThat(html).contains("role=\"presentation\"").doesNotContain("<svg", "http://", "https://", "display:flex");
    }
}
