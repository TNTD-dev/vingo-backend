package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import vingo.auth.AuthService;
import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.TokenSecrets;
import vingo.shared.security.AuthenticatedUser;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.ProblemAuthenticationHandlers;
import vingo.shared.security.Role;

class SessionAuthenticationFilterTest {
    private final String origin = "https://frontend.test";
    private final String token = TokenSecrets.generate();
    private final AuthService auth = mock(AuthService.class);
    private final ProblemAuthenticationHandlers problems = mock(ProblemAuthenticationHandlers.class);
    private final AuthCookies cookies = new AuthCookies(new AuthProperties(URI.create(origin), false,
            Duration.ofDays(7)), Clock.systemUTC());
    private final SessionAuthenticationFilter filter = new SessionAuthenticationFilter(auth, cookies, problems, origin);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), Role.BUYER, UUID.randomUUID(),
            OffsetDateTime.parse("2027-01-01T00:00:00Z"));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void foreignOriginIsRejectedBeforeAccountLookup() throws Exception {
        request.addHeader("Origin", "https://other.test");
        filter.doFilter(request, response, chain);
        verify(problems).handle(request, response, null);
        verifyNoInteractions(auth);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void cookieWriteRequiresOriginAndDoesNotRecordActivityWhenDenied() throws Exception {
        request.setCookies(new Cookie("vingo_session", token));
        when(auth.authenticate(token, null, request.getRemoteAddr(), false)).thenReturn(Optional.of(user));
        filter.doFilter(request, response, chain);
        verify(problems).handle(request, response, null);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void validOriginAuthenticatesCookieWithoutServletSession() throws Exception {
        request.addHeader("Origin", origin);
        request.setCookies(new Cookie("vingo_session", token));
        authenticate();
    }

    @Test
    void bearerWithoutOriginTakesPrecedenceOverCookie() throws Exception {
        request.addHeader("Authorization", "Bearer " + token);
        request.setCookies(new Cookie("vingo_session", TokenSecrets.generate()));
        authenticate();
    }

    private void authenticate() throws Exception {
        when(auth.authenticate(token, null, request.getRemoteAddr(), true)).thenReturn(Optional.of(user));
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(new CurrentUser().require()).isEqualTo(user);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void badAuthorizationHeaderPreventsCookieFallback() throws Exception {
        request.setCookies(new Cookie("vingo_session", token));
        request.addHeader("Authorization", "Basic invalid");
        filter.doFilter(request, response, chain);
        verify(problems).commence(request, response, null);
        verifyNoInteractions(auth);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void databaseFailurePropagatesToServerErrorHandling() {
        request.addHeader("Authorization", "Bearer " + token);
        when(auth.authenticate(token, null, request.getRemoteAddr(), true))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(problems);
    }
}
