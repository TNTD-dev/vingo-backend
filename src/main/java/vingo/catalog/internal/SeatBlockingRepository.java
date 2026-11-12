package vingo.catalog.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class SeatBlockingRepository {
    private final JdbcClient jdbc;
    public SeatBlockingRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<UUID> eventId(UUID id) {
        return jdbc.sql("SELECT event_id FROM showtime WHERE id=:id").param("id",id).query(UUID.class).optional();
    }
    public Optional<Session> lockSession(UUID id) {
        return jdbc.sql("SELECT seat_layout_id,status,ends_at FROM showtime WHERE id=:id FOR UPDATE")
            .param("id",id).query((rs,n)->new Session(rs.getObject("seat_layout_id",UUID.class),rs.getString("status"),rs.getObject("ends_at",OffsetDateTime.class))).optional();
    }
    public List<SeatState> lockSeats(UUID id, UUID layout, List<UUID> ids) {
        return jdbc.sql("""
            SELECT ss.seat_id,ss.status,ss.held_until FROM showtime_seat ss
            JOIN seat s ON s.id=ss.seat_id JOIN zone z ON z.id=s.zone_id
            WHERE ss.showtime_id=:id AND ss.seat_id=ANY(:ids)
            AND z.seat_layout_id=:layout AND z.kind='SEATED'
            ORDER BY ss.seat_id FOR UPDATE OF ss
            """).param("id",id).param("layout",layout).param("ids",new SqlArrayValue("uuid",ids.toArray()))
            .query((rs,n)->new SeatState(rs.getObject("seat_id",UUID.class),rs.getString("status"),rs.getObject("held_until",OffsetDateTime.class))).list();
    }
    public void update(UUID id,List<UUID> ids,boolean block,String reason,OffsetDateTime now) {
        int count=jdbc.sql("""
            UPDATE showtime_seat SET status=:status,order_id=NULL,held_until=NULL,version=version+1
            WHERE showtime_id=:id AND seat_id=ANY(:ids)
            """).param("status",block?"BLOCKED":"AVAILABLE").param("id",id).param("ids",new SqlArrayValue("uuid",ids.toArray())).update();
        if(count!=ids.size()) throw new IllegalStateException("Unexpected inventory update count");
        jdbc.sql("""
            INSERT INTO catalog_seat_block_log(showtime_id,action,seat_ids,reason,occurred_at)
            VALUES(:id,:action,:ids,:reason,:now)
            """).param("id",id).param("action",block?"BLOCK":"UNBLOCK").param("ids",new SqlArrayValue("uuid",ids.toArray()))
            .param("reason",reason,Types.VARCHAR).param("now",now).update();
    }
    public record Session(UUID layoutId,String status,OffsetDateTime endsAt) {}
    public record SeatState(UUID id,String status,OffsetDateTime heldUntil) {}
}
