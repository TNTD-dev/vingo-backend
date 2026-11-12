package vingo.realtime.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vingo.realtime.internal.RealtimeEventRepository.Event;

/** Một luồng gửi riêng, đệm live có giới hạn và phát lại không chiếm dung lượng đệm. */
public class Subscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Subscriber.class);
    private static final int RECENT_IDS = 1024;

    private final Object monitor = new Object();
    private final int queueCapacity;
    private final Consumer<Event> sink;
    private final Runnable onClose;
    private final Runnable onStopped;
    private final Thread sender;
    private final LinkedHashMap<UUID, Event> live = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> sent = new LinkedHashSet<>();
    private Supplier<List<Event>> initialReplay;
    private Supplier<List<Event>> recoveryReplay;
    private Runnable heartbeat;
    private Event latestEvent;
    private UUID excludedId;
    private boolean initialized;
    private volatile boolean closed;

    /** Bắt đầu giữ đệm live cho tới khi caller cung cấp danh sách phát lại. */
    public Subscriber(int queueCapacity, Consumer<Event> sink, Runnable onClose) {
        this(queueCapacity, sink, onClose, () -> { });
    }

    Subscriber(int queueCapacity, Consumer<Event> sink, Runnable onClose, Runnable onStopped) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity phải lớn hơn 0");
        }
        this.queueCapacity = queueCapacity;
        this.sink = sink;
        this.onClose = onClose;
        this.onStopped = onStopped;
        sender = Thread.ofVirtual().name("realtime-sender").unstarted(this::sendLoop);
        sender.start();
    }

    /** Nhận live không chờ sink; tràn đệm thì đóng và trả false. */
    public boolean offer(Event event) {
        synchronized (monitor) {
            if (closed) {
                return false;
            }
            if (event.id().equals(excludedId) || sent.contains(event.id()) || live.containsKey(event.id())) {
                return true;
            }
            if (live.size() < queueCapacity) {
                live.put(event.id(), event);
                monitor.notifyAll();
                return true;
            }
        }
        close();
        return false;
    }

    /** Gửi danh sách phát lại trên virtual thread, rồi xả đệm live sau khi khử trùng. */
    public void replay(List<Event> events) {
        replay(() -> events);
    }

    void replay(Supplier<List<Event>> query) {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            if (!initialized) {
                initialReplay = query;
                initialized = true;
            } else {
                recoveryReplay = query;
            }
            monitor.notifyAll();
        }
    }

    void catchUp(Supplier<List<Event>> query) {
        synchronized (monitor) {
            if (!closed) {
                // Chỉ cần một lượt bắt kịp chưa chạy; cursor được đọc khi thực sự truy vấn.
                recoveryReplay = query;
                monitor.notifyAll();
            }
        }
    }

    void heartbeat(Runnable sendPing) {
        synchronized (monitor) {
            if (!closed) {
                heartbeat = sendPing;
                monitor.notifyAll();
            }
        }
    }

    void exclude(UUID id) {
        synchronized (monitor) {
            excludedId = id;
            live.remove(id);
        }
    }

    Optional<Event> latestEvent() {
        synchronized (monitor) {
            return Optional.ofNullable(latestEvent);
        }
    }

    boolean awaitStopped(Duration timeout) throws InterruptedException {
        return sender.join(timeout);
    }

    private void sendLoop() {
        try {
            while (!closed) {
                Supplier<List<Event>> query = null;
                Event next = null;
                synchronized (monitor) {
                    while (!closed && (!initialized ||
                            (initialReplay == null && recoveryReplay == null && live.isEmpty() && heartbeat == null))) {
                        monitor.wait();
                    }
                    if (closed) {
                        break;
                    }
                    if (initialReplay != null) {
                        query = initialReplay;
                        initialReplay = null;
                    } else if (recoveryReplay != null) {
                        query = recoveryReplay;
                        recoveryReplay = null;
                    } else if (!live.isEmpty()) {
                        next = live.pollFirstEntry().getValue();
                    }
                }
                sendHeartbeat();
                if (closed) {
                    break;
                }
                if (query != null) {
                    for (Event event : query.get()) {
                        if (closed) {
                            break;
                        }
                        sendHeartbeat();
                        send(event);
                    }
                } else if (next != null) {
                    send(next);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.io.UncheckedIOException ex) {
            // Client ngắt kết nối là bình thường (đóng tab, mất mạng), không cần cảnh báo.
            log.debug("Realtime client disconnected");
        } catch (RuntimeException ex) {
            if (!closed) {
                log.warn("Realtime subscriber stopped, error_type={}", ex.getClass().getSimpleName());
            }
        } finally {
            try {
                close();
            } finally {
                // Complete sau khi sink thoát và tạm bỏ cờ ngắt để flush kết thúc SSE không lỗi I/O.
                boolean interrupted = Thread.interrupted();
                try {
                    onStopped.run();
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void sendHeartbeat() {
        Runnable ping;
        synchronized (monitor) {
            ping = heartbeat;
            heartbeat = null;
        }
        if (!closed && ping != null) {
            ping.run();
        }
    }

    private void send(Event event) {
        synchronized (monitor) {
            if (closed || event.id().equals(excludedId) || sent.contains(event.id())) {
                return;
            }
        }
        sink.accept(event);
        synchronized (monitor) {
            if (!closed) {
                sent.add(event.id());
                if (sent.size() > RECENT_IDS) {
                    sent.removeFirst();
                }
                // Replay dài hơn bộ nhớ id vẫn phải loại bản live đang đệm của từng sự kiện vừa gửi.
                live.remove(event.id());
                if (latestEvent == null || !event.createdAt().isBefore(latestEvent.createdAt())) {
                    latestEvent = event;
                }
            }
        }
    }

    /** Gỡ subscriber ngay và ngắt luồng gửi mà không chờ sink bị chặn. */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            live.clear();
            initialReplay = null;
            recoveryReplay = null;
            heartbeat = null;
            monitor.notifyAll();
        }
        if (Thread.currentThread() != sender) {
            sender.interrupt();
        }
        onClose.run();
    }
}
