package vingo.realtime.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/** Cửa sổ phát lại, hạn lưu và giới hạn tài nguyên của các stream. */
@ConfigurationProperties("vingo.realtime")
public record RealtimeProperties(
        Duration replayWindow,
        Duration replayOverlap,
        Duration retention,
        Duration heartbeat,
        int maxConnectionsPerUser,
        int queueCapacity) {

    /** Từ chối cấu hình khiến stream không có giới hạn hoặc mất cửa sổ phát lại. */
    public RealtimeProperties {
        Assert.notNull(replayWindow, "replay-window phải được cấu hình");
        Assert.notNull(replayOverlap, "replay-overlap phải được cấu hình");
        Assert.notNull(retention, "retention phải được cấu hình");
        Assert.notNull(heartbeat, "heartbeat phải được cấu hình");
        Assert.isTrue(replayWindow.isPositive(), "replay-window phải lớn hơn 0");
        Assert.isTrue(!replayOverlap.isNegative(), "replay-overlap không được âm");
        Assert.isTrue(retention.compareTo(replayWindow) >= 0, "retention phải chứa toàn cửa sổ phát lại");
        Assert.isTrue(heartbeat.toMillis() > 0, "heartbeat phải ít nhất 1ms");
        Assert.isTrue(maxConnectionsPerUser > 0 && queueCapacity > 0, "Giới hạn kết nối và hàng đợi phải lớn hơn 0");
    }
}
