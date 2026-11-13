package vingo.realtime.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import vingo.realtime.internal.RealtimeEventRepository.Event;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** Quản lý kết nối theo user và một scheduler chung cho heartbeat cùng hạn JWT. */
@Component
@Profile("api")
public class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

    private final Object monitor = new Object();
    private final Map<UUID, LinkedHashSet<Stream>> streams = new HashMap<>();
    private final Set<Subscriber> senders = new HashSet<>();
    private final RealtimeEventRepository events;
    private final RealtimeProperties properties;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor scheduler;
    private boolean stopping;

    /** Dùng chung scheduler; việc ghi mạng luôn do virtual thread của Subscriber thực hiện. */
    public StreamRegistry(RealtimeEventRepository events, RealtimeProperties properties, Clock clock) {
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        scheduler = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon(true).name("realtime-timer").factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.scheduleWithFixedDelay(this::heartbeat, properties.heartbeat().toMillis(),
                properties.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Đăng ký live trước truy vấn replay và đóng kết nối cũ nhất khi vượt giới hạn. */
    public SseEmitter open(UUID userId, Instant expiresAt, String lastEventId) {
        synchronized (monitor) {
            if (stopping) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Ứng dụng đang dừng");
            }
            var stream = new Stream(userId, expiresAt, lastEventId);
            var connections = streams.computeIfAbsent(userId, key -> new LinkedHashSet<>());
            connections.add(stream);
            senders.add(stream.subscriber);
            if (connections.size() > properties.maxConnectionsPerUser()) {
                connections.getFirst().subscriber.close();
            }
            log.info("Realtime stream opened, user_id={}, connections={}", userId, connections.size());
            scheduleExpiry(stream);
            // Giữ đệm đã được đăng ký trong map trước khi virtual thread bắt đầu đọc DB.
            stream.subscriber.replay(() -> {
                var replay = events.replay(userId, lastEventId);
                replay.cursor().ifPresent(stream.subscriber::exclude);
                return replay.events();
            });
            stream.subscriber.heartbeat(stream::ping);
            return stream.emitter;
        }
    }

    /** Trả snapshot các user có stream trên instance này để giới hạn truy vấn NOTIFY. */
    public Set<UUID> userIds() {
        synchronized (monitor) {
            return Set.copyOf(streams.keySet());
        }
    }

    /** Chuyển sự kiện tới đúng người nhận mà không đợi ghi ra mạng. */
    public void dispatch(Event event) {
        List<Stream> targets;
        synchronized (monitor) {
            var connections = streams.get(event.userId());
            targets = connections == null ? List.of() : List.copyOf(connections);
        }
        for (Stream stream : targets) {
            stream.subscriber.offer(event);
        }
    }

    /** Hẹn bắt kịp từng stream sau khi LISTEN được thiết lập lại. */
    public void catchUp() {
        for (Stream stream : snapshot()) {
            stream.subscriber.catchUp(() -> stream.subscriber.latestEvent()
                    .map(last -> events.replay(stream.userId, last.id().toString()).events())
                    .orElseGet(() -> stream.lastEventId != null
                            ? events.replay(stream.userId, stream.lastEventId).events()
                            : events.replaySince(stream.userId, stream.connectedAt)));
        }
    }

    private List<Stream> snapshot() {
        synchronized (monitor) {
            List<Stream> snapshot = new ArrayList<>();
            streams.values().forEach(snapshot::addAll);
            return snapshot;
        }
    }

    private void heartbeat() {
        Instant now = clock.instant();
        for (Stream stream : snapshot()) {
            if (!now.isBefore(stream.expiresAt)) {
                stream.subscriber.close();
            } else {
                stream.subscriber.heartbeat(stream::ping);
            }
        }
    }

    private void scheduleExpiry(Stream stream) {
        synchronized (monitor) {
            var connections = streams.get(stream.userId);
            if (stopping || connections == null || !connections.contains(stream)) {
                return;
            }
            Duration remaining = Duration.between(clock.instant(), stream.expiresAt);
            if (!remaining.isPositive()) {
                stream.subscriber.close();
            } else {
                // Tính lại theo Clock khi lịch chạy để không đóng sớm nếu đồng hồ được điều chỉnh.
                stream.expiry = scheduler.schedule(() -> scheduleExpiry(stream),
                        remaining.toNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }

    private void remove(Stream stream) {
        synchronized (monitor) {
            if (stream.expiry != null) {
                stream.expiry.cancel(false);
            }
            var connections = streams.get(stream.userId);
            if (connections != null && connections.remove(stream)) {
                int count = connections.size();
                if (connections.isEmpty()) {
                    streams.remove(stream.userId);
                }
                log.info("Realtime stream closed, user_id={}, connections={}", stream.userId, count);
            }
        }
    }

    /** Đóng mọi emitter trước graceful shutdown, dừng timer và chờ luồng gửi tối đa 5 giây tổng cộng. */
    @PreDestroy
    public void close() {
        List<Subscriber> remaining;
        synchronized (monitor) {
            if (stopping) {
                return;
            }
            stopping = true;
            remaining = List.copyOf(senders);
        }
        scheduler.shutdownNow();
        remaining.forEach(Subscriber::close);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        for (Subscriber subscriber : remaining) {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                break;
            }
            try {
                subscriber.awaitStopped(Duration.ofNanos(nanos));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (monitor) {
            if (!senders.isEmpty()) {
                log.warn("Realtime senders still stopping, count={}", senders.size());
            }
        }
    }

    private final class Stream {

        private final UUID userId;
        private final Instant expiresAt;
        private final String lastEventId;
        private final OffsetDateTime connectedAt;
        private final SseEmitter emitter = new SseEmitter(0L);
        private final Subscriber subscriber;
        private ScheduledFuture<?> expiry;

        private Stream(UUID userId, Instant expiresAt, String lastEventId) {
            this.userId = userId;
            this.expiresAt = expiresAt;
            this.lastEventId = lastEventId;
            this.connectedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            subscriber = new Subscriber(properties.queueCapacity(), this::send,
                    () -> remove(this), this::complete);
            emitter.onCompletion(subscriber::close);
            emitter.onTimeout(subscriber::close);
            emitter.onError(error -> subscriber.close());
        }

        private void send(Event event) {
            send(SseEmitter.event().id(event.id().toString()).name(event.type())
                    .data(event.payload(), MediaType.APPLICATION_JSON));
        }

        private void ping() {
            send(SseEmitter.event().comment("ping"));
        }

        private void send(SseEmitter.SseEventBuilder event) {
            if (!clock.instant().isBefore(expiresAt)) {
                subscriber.close();
                return;
            }
            try {
                emitter.send(event);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private void complete() {
            try {
                emitter.complete();
            } finally {
                synchronized (monitor) {
                    senders.remove(subscriber);
                }
            }
        }
    }
}
