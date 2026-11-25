package vingo.auth.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.MeApi;
import vingo.api.model.SessionInfo;
import vingo.api.model.UpdateMeRequest;
import vingo.api.model.User;
import vingo.auth.AuthService;
import vingo.auth.AuthUser;
import vingo.shared.security.CurrentUser;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class MeController implements MeApi {

    private final AuthService auth;
    private final CurrentUser currentUser;
    private final AuthCookies cookies;

    MeController(AuthService auth, CurrentUser currentUser, AuthCookies cookies) {
        this.auth = auth;
        this.currentUser = currentUser;
        this.cookies = cookies;
    }

    @Override
    public ResponseEntity<User> getMe() {
        return ResponseEntity.ok(toUser(auth.getMe(currentUser.require().id())));
    }

    @Override
    public ResponseEntity<User> updateMe(UpdateMeRequest body) {
        var user = currentUser.require();
        var updated = auth.updateMe(user, body.getFullName(), body.getPhone(),
                body.getCurrentPassword(), body.getNewPassword());
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        updated.replacementToken().ifPresent(token -> response.header(HttpHeaders.SET_COOKIE,
                cookies.session(token, user.expiresAt()).toString()));
        return response.body(toUser(updated.user()));
    }

    @Override
    public ResponseEntity<Void> deleteMe() {
        auth.deleteMe(currentUser.require().id());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header("Set-Cookie", cookies.clearSession().toString())
                .build();
    }

    @Override
    public ResponseEntity<List<SessionInfo>> listSessions() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.sessions(currentUser.require()));
    }

    @Override
    public ResponseEntity<Void> deleteSession(UUID sessionId) {
        var user = currentUser.require();
        auth.deleteSession(user.id(), sessionId);
        var response = ResponseEntity.noContent().cacheControl(CacheControl.noStore());
        if (user.sessionId().equals(sessionId)) {
            response.header(HttpHeaders.SET_COOKIE, cookies.clearSession().toString());
        }
        return response.build();
    }

    private static User toUser(AuthUser account) {
        return new User().id(account.id()).email(account.email()).phone(account.phone())
                .fullName(account.fullName()).avatarUrl(account.avatar())
                .role(User.RoleEnum.fromValue(account.role().name()))
                .emailVerified(!"UNVERIFIED".equals(account.status()));
    }
}
