package vingo.realtime.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Đọc sự kiện theo người nhận, cửa sổ phát lại và lô id thông báo. */
@Repository
public class RealtimeEventRepository {

    private static final RowMapper<Event> MAPPER = (rs, row) -> new Event(
            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getString("type"), rs.getString("payload"), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RealtimeProperties properties;

    /** Dùng JDBC và đồng hồ nghiệp vụ để tính cửa sổ phát lại. */
    public RealtimeEventRepository(JdbcClient jdbc, Clock clock, RealtimeProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    /** Trả sự kiện của user theo cursor, hoặc toàn cửa sổ nếu cursor không hợp lệ. */
    public Replay replay(UUID userId, String lastEventId) {
        if (lastEventId == null) {
            return new Replay(List.of(), Optional.empty());
        }
        OffsetDateTime cutoff = cutoff();
        Optional<UUID> id = parseId(lastEventId);
        if (id.isPresent()) {
            var createdAt = jdbc.sql("""
                            SELECT created_at FROM realtime_event
                            WHERE user_id = :user AND id = :id AND created_at >= :cutoff
                            """)
                    .param("user", userId).param("id", id.get()).param("cutoff", cutoff)
                    .query(OffsetDateTime.class).optional();
            if (createdAt.isPresent()) {
                OffsetDateTime overlap = createdAt.get().minus(properties.replayOverlap());
                return new Replay(readWindow(userId, overlap.isAfter(cutoff) ? overlap : cutoff, id), id);
            }
        }
        return new Replay(readWindow(userId, cutoff, Optional.empty()), Optional.empty());
    }

    /** Bắt kịp từ lúc đăng ký nếu kết nối chưa gửi được sự kiện nào. */
    public List<Event> replaySince(UUID userId, OffsetDateTime connectedAt) {
        OffsetDateTime cutoff = cutoff();
        return readWindow(userId, connectedAt.isAfter(cutoff) ? connectedAt : cutoff, Optional.empty());
    }

    /** Đọc một lô thông báo, chỉ lấy user đang có kết nối được xác thực trên instance này. */
    public List<Event> findByIds(Collection<UUID> ids, Collection<UUID> users) {
        if (ids.isEmpty() || users.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, user_id, type, payload, created_at FROM realtime_event
                        WHERE id = ANY(:ids) AND user_id = ANY(:users) ORDER BY id
                        """)
                .param("ids", new SqlArrayValue("uuid", ids.toArray(UUID[]::new)))
                .param("users", new SqlArrayValue("uuid", users.toArray(UUID[]::new)))
                .query(MAPPER).list();
    }

    static Optional<UUID> parseId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private OffsetDateTime cutoff() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(properties.replayWindow());
    }

    private List<Event> readWindow(UUID userId, OffsetDateTime from, Optional<UUID> excluded) {
        String sql = """
                SELECT id, user_id, type, payload, created_at FROM realtime_event
                WHERE user_id = :user AND created_at >= :from
                """;
        var query = jdbc.sql(sql + (excluded.isPresent() ? " AND id <> :excluded" : "") + " ORDER BY id")
                .param("user", userId).param("from", from);
        excluded.ifPresent(id -> query.param("excluded", id));
        return query.query(MAPPER).list();
    }

    /** Kết quả phát lại cùng cursor đã xác nhận tồn tại, đúng chủ và còn trong cửa sổ. */
    public record Replay(List<Event> events, Optional<UUID> cursor) {
    }

    /** Sự kiện đã commit với payload JSON nguyên bản và thời điểm nghiệp vụ. */
    public record Event(UUID id, UUID userId, String type, String payload, OffsetDateTime createdAt) {
    }
}
