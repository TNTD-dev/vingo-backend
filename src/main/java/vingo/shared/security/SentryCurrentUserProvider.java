package vingo.shared.security;

import java.util.Map;

import io.sentry.protocol.User;
import io.sentry.spring7.SentryUserProvider;
import org.springframework.stereotype.Component;

/**
 * Gắn user hiện tại vào event/trace Sentry để lọc theo {@code user.id}.
 *
 * <p>Chỉ gửi UUID và role. Không gửi email, số điện thoại hay IP ({@code sentry.send-default-pii} vẫn tắt).
 * Request chưa đăng nhập thì không gắn gì.
 */
@Component
class SentryCurrentUserProvider implements SentryUserProvider {

    private final CurrentUser currentUser;

    SentryCurrentUserProvider(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public User provideUser() {
        return currentUser.find().map(u -> {
            User user = new User();
            user.setId(u.id().toString());
            user.setData(Map.of("role", u.role().name()));
            return user;
        }).orElse(null);
    }
}
