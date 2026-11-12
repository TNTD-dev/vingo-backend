package vingo.realtime.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.UUID;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/** LISTEN bằng kết nối riêng ngoài pool và bắt kịp các stream sau mỗi lần nối lại. */
@Component
@Profile("api")
public class PgNotificationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PgNotificationListener.class);
    private static final int BATCH_SIZE = 256;

    private final JdbcConnectionDetails connectionDetails;
    private final RealtimeEventRepository events;
    private final StreamRegistry streams;
    private volatile boolean running;
    private volatile Connection connection;
    private Thread listener;

    /** Lấy cả cấu hình DB thật lẫn ServiceConnection của Testcontainers qua cùng một bean. */
    public PgNotificationListener(JdbcConnectionDetails connectionDetails, RealtimeEventRepository events,
                                  StreamRegistry streams) {
        this.connectionDetails = connectionDetails;
        this.events = events;
        this.streams = streams;
    }

    /** Khởi động một virtual thread dành riêng cho kết nối LISTEN. */
    @Override
    public synchronized void start() {
        if (!running) {
            running = true;
            listener = Thread.ofVirtual().name("realtime-listener").start(this::listen);
        }
    }

    private void listen() {
        int backoffSeconds = 1;
        boolean reconnecting = false;
        while (running) {
            try (Connection opened = DriverManager.getConnection(connectionDetails.getJdbcUrl(), connectionProperties())) {
                connection = opened;
                if (!running) {
                    break;
                }
                opened.setAutoCommit(true);
                JdbcClient.create(new SingleConnectionDataSource(opened, true))
                        .sql("LISTEN realtime_event").update();
                PGConnection pg = opened.unwrap(PGConnection.class);
                if (reconnecting) {
                    log.warn("Realtime listener reconnected; scheduling catch-up");
                }
                backoffSeconds = 1;
                // LISTEN trước truy vấn bắt kịp để không tạo thêm khoảng trống giữa replay và live.
                streams.catchUp();
                while (running) {
                    dispatch(pg.getNotifications(1000));
                }
            } catch (SQLException | RuntimeException ex) {
                if (running) {
                    log.warn("Realtime listener disconnected, retry_seconds={}, error_type={}",
                            backoffSeconds, ex.getClass().getSimpleName());
                    reconnecting = true;
                }
            } finally {
                connection = null;
            }
            if (running) {
                try {
                    Thread.sleep(Duration.ofSeconds(backoffSeconds));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffSeconds = Math.min(30, backoffSeconds * 2);
            }
        }
    }

    private Properties connectionProperties() {
        var properties = new Properties();
        if (connectionDetails.getUsername() != null) {
            properties.setProperty("user", connectionDetails.getUsername());
        }
        if (connectionDetails.getPassword() != null) {
            properties.setProperty("password", connectionDetails.getPassword());
        }
        properties.setProperty("ApplicationName", "vingo-realtime-listener");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "5");
        return properties;
    }

    private void dispatch(PGNotification[] notifications) {
        if (notifications == null) {
            return;
        }
        var ids = new LinkedHashSet<UUID>();
        for (PGNotification notification : notifications) {
            if (!running) {
                return;
            }
            if ("realtime_event".equals(notification.getName())) {
                RealtimeEventRepository.parseId(notification.getParameter()).ifPresent(ids::add);
            }
            if (ids.size() == BATCH_SIZE) {
                deliver(ids);
                ids.clear();
            }
        }
        deliver(ids);
    }

    private void deliver(LinkedHashSet<UUID> ids) {
        for (var event : events.findByIds(ids, streams.userIds())) {
            streams.dispatch(event);
        }
    }

    /** Đóng LISTEN và mọi emitter ở phase mặc định, trước graceful shutdown của web server. */
    @Override
    public synchronized void stop() {
        running = false;
        if (listener != null) {
            listener.interrupt();
        }
        Connection current = connection;
        try {
            if (current != null) {
                current.close();
            }
        } catch (SQLException ex) {
            log.warn("Realtime listener close failed, error_type={}", ex.getClass().getSimpleName());
        } finally {
            streams.close();
        }
        if (listener != null) {
            try {
                if (!listener.join(Duration.ofSeconds(5))) {
                    log.warn("Realtime listener is still stopping");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Cho Spring biết listener đang được quản lý và cần dừng khi đóng context. */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** Không tái sử dụng registry đã đóng trong chu kỳ pause của context. */
    @Override
    public boolean isPauseable() {
        return false;
    }
}
