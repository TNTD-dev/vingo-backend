package vingo.auth.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import vingo.auth.AuthService;
import vingo.shared.security.AuthenticatedUser;
import vingo.shared.security.ProblemAuthenticationHandlers;

final class SessionAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final PathPatternRequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");
    private final RequestAttributeSecurityContextRepository contexts = new RequestAttributeSecurityContextRepository();
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuthService auth;
    private final AuthCookies cookies;
    private final ProblemAuthenticationHandlers problems;
    private final String frontendOrigin;

    SessionAuthenticationFilter(AuthService auth, AuthCookies cookies, ProblemAuthenticationHandlers problems,
                                String frontendOrigin) {
        this.auth = auth;
        this.cookies = cookies;
        this.problems = problems;
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        boolean write = WRITES.contains(request.getMethod()) && API.matches(request);
        var origins = request.getHeaders(HttpHeaders.ORIGIN);
        String origin = origins.hasMoreElements() ? origins.nextElement() : null;
        if (write && origin != null && (!frontendOrigin.equals(origin) || origins.hasMoreElements())) {
            problems.handle(request, response, null);
            return;
        }

        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        boolean bearer = headers.hasMoreElements();
        String token;
        if (bearer) {
            String header = headers.nextElement();
            token = !headers.hasMoreElements() && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? header.substring(7) : "";
        } else {
            token = cookies.read(request, AuthCookies.SESSION).orElse("");
        }
        boolean missingOrigin = write && !bearer && origin == null;
        Optional<AuthenticatedUser> user = TOKEN.matcher(token).matches()
                ? auth.authenticate(token, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr(), !missingOrigin)
                : Optional.empty();
        if (bearer && user.isEmpty()) {
            problems.commence(request, response, null);
            return;
        }
        if (user.isPresent()) {
            if (missingOrigin) {
                problems.handle(request, response, null);
                return;
            }
            var principal = user.get();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }
}
