package vingo.catalog.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class SeatBlockingRepository {

    private final JdbcClient jdbc;

    public SeatBlockingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> eventId(UUID id) {
        return jdbc.sql("SELECT event_id FROM showtime WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    public Optional<Session> lockSession(UUID id) {
        return jdbc.sql("SELECT seat_map_id, cancelled_at, ends_at FROM showtime WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id)
                .query((rs, n) -> new Session(
                        rs.getObject("seat_map_id", UUID.class),
                        rs.getObject("cancelled_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class)))
                .optional();
    }

    public List<SeatState> lockSeats(UUID id, UUID layout, List<UUID> ids) {
        return jdbc.sql("""
                SELECT si.seat_id, si.status::text AS status, si.held_until
                FROM seat_inventory si
                WHERE si.showtime_id = :id
                  AND si.seat_id = ANY(:ids)
                  AND si.seat_map_id = :layout
                ORDER BY si.seat_id
                FOR UPDATE OF si
                """)
                .param("id", id)
                .param("layout", layout)
                .param("ids", new SqlArrayValue("uuid", ids.toArray()))
                .query((rs, n) -> new SeatState(
                        rs.getObject("seat_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("held_until", OffsetDateTime.class)))
                .list();
    }

    public void update(UUID showtimeId, UUID actorId, List<UUID> ids, boolean block, String reason, OffsetDateTime now) {
        int count = jdbc.sql("""
                UPDATE seat_inventory
                SET status = CAST(:status AS seat_status), order_id = NULL, held_until = NULL
                WHERE showtime_id = :id AND seat_id = ANY(:ids)
                """)
                .param("status", block ? "BLOCKED" : "AVAILABLE")
                .param("id", showtimeId)
                .param("ids", new SqlArrayValue("uuid", ids.toArray()))
                .update();
        if (count != ids.size()) {
            throw new IllegalStateException("Unexpected inventory update count");
        }
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, entity, entity_id, reason, at, after)
                VALUES (:actor, :action, 'showtime', :id, :reason, :now,
                        jsonb_build_object('seat_ids', to_jsonb(CAST(:ids AS uuid[]))))
                """)
                .param("actor", actorId)
                .param("action", block ? "BLOCK_SEATS" : "UNBLOCK_SEATS")
                .param("id", showtimeId)
                .param("reason", reason, Types.VARCHAR)
                .param("now", now)
                .param("ids", new SqlArrayValue("uuid", ids.toArray()))
                .update();
    }

    public record Session(UUID layoutId, OffsetDateTime cancelledAt, OffsetDateTime endsAt) {}

    public record SeatState(UUID id, String status, OffsetDateTime heldUntil) {}
}
