package vingo.auth.web.oauth2;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import tools.jackson.databind.json.JsonMapper;
import vingo.auth.internal.GoogleAuthorizationRequestRepository;
import vingo.auth.internal.TokenSecrets;
import vingo.auth.web.AuthCookies;

/** Keeps each pending authorization request available to every API replica. */
class GoogleAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final GoogleAuthorizationRequestRepository requests;
    private final JsonMapper json;
    private final AuthCookies cookies;
    private final Clock clock;

    GoogleAuthorizationRequests(GoogleAuthorizationRequestRepository requests, JsonMapper json,
                                AuthCookies cookies, Clock clock) {
        this.requests = requests;
        this.json = json;
        this.cookies = cookies;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return findRequest(request, false);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorization == null) {
            removeAuthorizationRequest(request, response);
            return;
        }

        String browserSecret = TokenSecrets.generate();
        OffsetDateTime now = OffsetDateTime.now(clock);
        requests.save(TokenSecrets.hash(authorization.getState()), TokenSecrets.hash(browserSecret),
                json.writeValueAsString(SavedRequest.from(authorization)), now, now.plus(TTL));
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.cookie(AuthCookies.OAUTH_BROWSER, browserSecret, TTL).toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest authorization = findRequest(request, true);
        if (authorization != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    cookies.cookie(AuthCookies.OAUTH_BROWSER, "", Duration.ZERO).toString());
        }
        return authorization;
    }

    private OAuth2AuthorizationRequest findRequest(HttpServletRequest request, boolean consume) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        String browserSecret = cookies.read(request, AuthCookies.OAUTH_BROWSER).orElse("");
        if (state == null || state.length() > 256 || browserSecret.length() != 43) {
            return null;
        }
        String stateHash = TokenSecrets.hash(state);
        String browserHash = TokenSecrets.hash(browserSecret);
        OffsetDateTime now = OffsetDateTime.now(clock);
        var saved = consume ? requests.consume(stateHash, browserHash, now) : requests.find(stateHash, browserHash, now);
        return saved
                .map(value -> json.readValue(value, SavedRequest.class).toAuthorizationRequest())
                .orElse(null);
    }

    private record SavedRequest(String authorizationUri, String clientId, String redirectUri,
                                Set<String> scopes, String state, Map<String, Object> parameters,
                                Map<String, Object> attributes, String requestUri) {

        static SavedRequest from(OAuth2AuthorizationRequest request) {
            return new SavedRequest(request.getAuthorizationUri(), request.getClientId(), request.getRedirectUri(),
                    request.getScopes(), request.getState(), request.getAdditionalParameters(),
                    request.getAttributes(), request.getAuthorizationRequestUri());
        }

        OAuth2AuthorizationRequest toAuthorizationRequest() {
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(authorizationUri).clientId(clientId).redirectUri(redirectUri)
                    .scopes(scopes).state(state).additionalParameters(parameters)
                    .attributes(attributes).authorizationRequestUri(requestUri).build();
        }
    }
}
