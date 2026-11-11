package vingo.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * {@link Clock} cho test: mặc định chạy theo giờ hệ thống, gọi {@link #set(Instant)} để đóng băng ở một thời điểm.
 *
 * <p>Là bean {@code @Primary} trong test integration nên code main lấy giờ qua Clock sẽ thấy giờ này.
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    /** Đóng băng giờ ở thời điểm {@code instant}. */
    public void set(Instant instant) {
        this.fixed = instant;
    }

    /** Dời giờ đã đóng băng đi một khoảng. */
    public void advance(Duration duration) {
        this.fixed = instant().plus(duration);
    }

    /** Trả về giờ hệ thống thật. */
    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant current = fixed;
        return current != null ? current : Instant.now();
    }
}
