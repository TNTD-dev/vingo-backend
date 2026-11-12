package vingo.realtime.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import vingo.api.RealtimeApi;
import vingo.realtime.internal.StreamRegistry;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.JwtProperties;

/** Mở SSE của người đăng nhập theo contract và hạn của access token. */
@RestController
@Profile("api")
public class RealtimeController implements RealtimeApi {

    private final CurrentUser currentUser;
    private final StreamRegistry streams;
    private final Clock clock;
    private final JwtProperties jwtProperties;

    /** Nhận danh tính đã xác thực và các thành phần quản lý stream. */
    public RealtimeController(CurrentUser currentUser, StreamRegistry streams, Clock clock,
                              JwtProperties jwtProperties) {
        this.currentUser = currentUser;
        this.streams = streams;
        this.clock = clock;
        this.jwtProperties = jwtProperties;
    }

    /** Đăng ký nhận live trước khi phát lại và đóng stream đúng hạn JWT. */
    @Override
    public ResponseEntity<SseEmitter> streamMyEvents(String lastEventId) {
        var user = currentUser.require();
        var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Instant expiresAt = principal instanceof Jwt jwt && jwt.getExpiresAt() != null
                ? jwt.getExpiresAt() : clock.instant().plus(jwtProperties.accessTokenTtl());
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(streams.open(user.id(), expiresAt, lastEventId));
    }
}
