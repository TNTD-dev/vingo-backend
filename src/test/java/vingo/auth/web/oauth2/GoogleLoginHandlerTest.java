package vingo.auth.web.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import vingo.auth.application.GoogleLoginService;
import vingo.auth.internal.AuthProperties;
import vingo.auth.web.AuthCookies;

@ExtendWith(OutputCaptureExtension.class)
class GoogleLoginHandlerTest {

    /** Lỗi runtime trả failed và chỉ log lý do chung, kể cả khi exception có dữ liệu nhạy cảm. */
    @Test
    void success_redirectsToFailedWithoutLoggingExceptionMessage(CapturedOutput output) throws Exception {
        var login = mock(GoogleLoginService.class);
        String sensitiveMessage = "fixture-email@example.com fixture-token";
        when(login.login(any())).thenThrow(new IllegalStateException(sensitiveMessage));
        var properties = new AuthProperties(URI.create("http://localhost:3000/auth/google"), false, Duration.ofDays(30));
        var handler = new GoogleLoginHandler(login, properties, mock(AuthCookies.class));
        var claims = OidcIdToken.withTokenValue("fixture-id-token").subject("test-user")
                .claim("email", "fixture-email@example.com").claim("email_verified", true).build();
        var auth = new TestingAuthenticationToken(new DefaultOidcUser(List.of(), claims), null);
        var response = new MockHttpServletResponse();

        handler.success(new MockHttpServletRequest(), response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/auth/google?google_login=failed");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(output.getAll()).contains("Google sign-in failed: reason=unexpected_error")
                .doesNotContain("fixture-email@example.com", "fixture-token", "fixture-id-token");
    }
}
