package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import vingo.auth.internal.AuthRepository;
import vingo.auth.internal.AuthResponse;
import vingo.auth.internal.TokenSecrets;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.JwtProperties;
import vingo.shared.security.SecurityConfig;

@Service
public class AuthService {

    private final AuthRepository accounts;
    private final JwtProperties jwt;
    private final Clock clock;
    private final JwtEncoder encoder;

    /** Dùng cùng cấu hình ký JWT mà resource server dùng để xác minh. */
    AuthService(AuthRepository accounts, JwtProperties jwt, Clock clock) {
        this.accounts = accounts;
        this.jwt = jwt;
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(jwt)).build();
    }

    /** Xoay vòng refresh token và ký access JWT trong một transaction. */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken) || refreshToken.length() > 512) {
            throw invalidRefresh();
        }
        String nextToken = TokenSecrets.generate();
        OffsetDateTime now = OffsetDateTime.now(clock);
        var session = accounts.rotate(TokenSecrets.hash(refreshToken), TokenSecrets.hash(nextToken), now)
                .orElseThrow(AuthService::invalidRefresh);
        var claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(session.account().id().toString())
                .claim(CurrentUser.ROLE_CLAIM, session.account().role().name())
                .claim(CurrentUser.SESSION_CLAIM, session.id().toString())
                .issuedAt(now.toInstant())
                .expiresAt(now.plus(jwt.accessTokenTtl()).toInstant())
                .build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AuthResponse(accessToken, nextToken, session.expiresAt(),
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), session.account());
    }

    /** Thu hồi phiên thuộc user hiện tại hoặc mọi phiên của user đó. */
    @Transactional
    public void logout(UUID userId, UUID sessionId, boolean allDevices) {
        accounts.revoke(userId, sessionId, allDevices, OffsetDateTime.now(clock));
    }

    /** Dùng chung phản hồi cho refresh token sai, hết hạn hoặc đã thu hồi. */
    private static DomainException invalidRefresh() {
        return new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token không hợp lệ hoặc đã hết hạn");
    }
}
