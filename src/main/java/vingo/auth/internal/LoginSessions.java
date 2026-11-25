package vingo.auth.internal;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vingo.auth.AuthUser;
import vingo.notifications.NotificationsService;

@Component
public class LoginSessions {
    // 32 byte Base64 URL không padding; 2 bit thấp của ký tự cuối bằng 0.
    private static final Pattern DEVICE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");
    private final AuthRepository accounts;
    private final AuthProperties properties;
    private final NotificationsService notifications;

    public LoginSessions(AuthRepository accounts, AuthProperties properties, NotificationsService notifications) {
        this.accounts = accounts;
        this.properties = properties;
        this.notifications = notifications;
    }

    /** Caller đã khoá dòng user bằng FOR UPDATE hoặc vừa INSERT user trong cùng transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthResponse open(AuthUser user, OffsetDateTime now, LoginClient client) {
        String device = client.deviceToken();
        boolean issueCookie = device == null || !DEVICE_TOKEN.matcher(device).matches();
        if (issueCookie) device = TokenSecrets.generate();

        String token = TokenSecrets.generate();
        OffsetDateTime expiresAt = now.plus(properties.sessionTtl());
        accounts.createSession(user.id(), TokenSecrets.hash(token), now, expiresAt, client.agent(), client.ip());
        if (accounts.recordDevice(user.id(), TokenSecrets.hash(device), now)) {
            notifications.enqueueNewDevice(user.email(), client.agent(), client.ip(), now,
                    properties.frontendReturnUrl().resolve("/forgot-password"));
        }
        return new AuthResponse(token, expiresAt, user, issueCookie ? device : null);
    }
}
