package vingo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.AuthRepository;
import vingo.auth.internal.GoogleLoginRequest;
import vingo.auth.internal.TokenSecrets;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.Role;

class GoogleLoginServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration SESSION_TTL = Duration.ofDays(30);
    private static final String SUBJECT = "google-subject";
    private static final String EMAIL = "buyer@gmail.com";

    private final AuthRepository accounts = mock(AuthRepository.class);
    private final GoogleLoginService login = new GoogleLoginService(accounts,
            new AuthProperties(URI.create("http://localhost:3000/auth/google"), false, SESSION_TTL), CLOCK);

    /** Email chuẩn hoá được dùng làm tên khi Google thiếu thông tin hồ sơ. */
    @Test
    void newAccount_usesNormalizedEmailAsNameWhenGoogleOmitsProfile() {
        var account = account("ACTIVE");
        when(accounts.findByGoogleSubject(SUBJECT)).thenReturn(Optional.empty());
        when(accounts.createGoogleAccount(SUBJECT, EMAIL, EMAIL, null)).thenReturn(Optional.of(account));

        var result = login.login(new GoogleLoginRequest(SUBJECT, " Buyer@gmail.com ", true, null, null));

        assertThat(result.created()).isTrue();
        assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.now(CLOCK).plus(SESSION_TTL));
        verify(accounts).createSession(account.id(), TokenSecrets.hash(result.refreshToken()), result.expiresAt());
    }

    /** User đã khoá không được tạo phiên mới. */
    @Test
    void existingLockedAccount_cannotStartSession() {
        when(accounts.findByGoogleSubject(SUBJECT)).thenReturn(Optional.of(account("LOCKED")));

        assertThatThrownBy(() -> login.login(new GoogleLoginRequest(SUBJECT, EMAIL, true, null, null)))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(accounts, never()).createSession(any(), any(), any());
    }

    /** Tạo buyer Google với trạng thái tài khoản mà test cần. */
    private static AuthUser account(String status) {
        return new AuthUser(UUID.randomUUID(), EMAIL, null, EMAIL, null, Role.BUYER, status);
    }
}
