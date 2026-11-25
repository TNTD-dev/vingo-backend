package vingo.realtime.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import vingo.api.RealtimeApi;
import vingo.realtime.internal.StreamRegistry;
import vingo.shared.security.CurrentUser;

/** Mở SSE của người đăng nhập theo contract và hạn của phiên. */
@RestController
@Profile("api")
public class RealtimeController implements RealtimeApi {

    private final CurrentUser currentUser;
    private final StreamRegistry streams;
    private final Clock clock;
    private final Duration streamMax;

    /** Nhận danh tính đã xác thực và các thành phần quản lý stream. */
    public RealtimeController(CurrentUser currentUser, StreamRegistry streams, Clock clock,
                              @Value("${vingo.auth.stream-max}") Duration streamMax) {
        this.currentUser = currentUser;
        this.streams = streams;
        this.clock = clock;
        if (!streamMax.isPositive() || streamMax.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("AUTH_STREAM_MAX must be positive and at most PT15M");
        }
        this.streamMax = streamMax;
    }

    /** Đăng ký nhận live trước khi phát lại và đóng stream đúng hạn phiên. */
    @Override
    public ResponseEntity<SseEmitter> streamMyEvents(String lastEventId) {
        var user = currentUser.require();
        Instant limit = clock.instant().plus(streamMax);
        Instant expiresAt = user.expiresAt().toInstant();
        if (limit.isBefore(expiresAt)) {
            expiresAt = limit;
        }
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(streams.open(user.id(), expiresAt, lastEventId));
    }
}
