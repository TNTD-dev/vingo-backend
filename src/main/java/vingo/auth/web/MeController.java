package vingo.auth.web;

import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.MeApi;
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
        AuthUser updated = auth.updateMe(currentUser.require().id(), body.getFullName(), body.getPhone(),
                body.getCurrentPassword(), body.getNewPassword());
        return ResponseEntity.ok(toUser(updated));
    }

    @Override
    public ResponseEntity<Void> deleteMe() {
        auth.deleteMe(currentUser.require().id());
        return ResponseEntity.noContent()
                .header("Set-Cookie", cookies.cookie(AuthCookies.REFRESH, "", java.time.Duration.ZERO).toString())
                .build();
    }

    private static User toUser(AuthUser account) {
        return new User().id(account.id()).email(account.email()).phone(account.phone())
                .fullName(account.fullName()).avatarUrl(account.avatar())
                .role(User.RoleEnum.fromValue(account.role().name()))
                .emailVerified(!"UNVERIFIED".equals(account.status()));
    }
}
