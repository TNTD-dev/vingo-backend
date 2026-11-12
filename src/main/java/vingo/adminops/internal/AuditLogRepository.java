package vingo.adminops.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import vingo.api.model.AuditLog;

/** Ghi và đọc audit bằng JdbcClient trong transaction của AdminOpsService. */
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** Dùng mapper chung để lưu JSONB và trả snapshot dưới dạng object. */
    public AuditLogRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Ghi actor, lý do và snapshot riêng biệt với thời điểm do Clock của caller cung cấp. */
    public UUID insert(UUID actorId, String action, String entity, UUID entityId, String reason,
                       OffsetDateTime at, Map<String, Object> before, Map<String, Object> after) {
        return jdbc.sql("""
                        INSERT INTO audit_log (actor_id, action, entity, entity_id, reason, via_agent, at, before, after)
                        VALUES (:actor, :action, :entity, :id, :reason, false, :at,
                                CAST(:before AS jsonb), CAST(:after AS jsonb)) RETURNING id
                        """)
                .param("actor", actorId).param("action", action).param("entity", entity).param("id", entityId)
                .param("reason", reason, Types.VARCHAR).param("at", at)
                .param("before", before == null ? null : json.writeValueAsString(before), Types.VARCHAR)
                .param("after", after == null ? null : json.writeValueAsString(after), Types.VARCHAR)
                .query(UUID.class).single();
    }

    /** Đọc keyset at/id giảm dần và giải mã snapshot JSONB thành object. */
    public List<AuditLog> find(String entity, UUID entityId, OffsetDateTime beforeAt, UUID beforeId, int limit) {
        var query = jdbc.sql("""
                        SELECT id, actor_id, action, entity, entity_id, reason, via_agent, at,
                               before::text AS before_json, after::text AS after_json FROM audit_log WHERE true
                        """
                        + (entity == null ? "" : " AND entity = :entity")
                        + (entityId == null ? "" : " AND entity_id = :entityId")
                        + (beforeAt == null ? "" : " AND (at, id) < (:at, :id)")
                        + " ORDER BY at DESC, id DESC LIMIT :limit")
                .param("limit", limit);
        if (entity != null) {
            query.param("entity", entity);
        }
        if (entityId != null) {
            query.param("entityId", entityId);
        }
        if (beforeAt != null) {
            query.param("at", beforeAt).param("id", beforeId);
        }
        return query.query((rs, row) -> new AuditLog().id(rs.getObject("id", UUID.class))
                .actorId(rs.getObject("actor_id", UUID.class)).action(AuditLog.ActionEnum.fromValue(rs.getString("action")))
                .entity(rs.getString("entity")).entityId(rs.getObject("entity_id", UUID.class))
                .reason(rs.getString("reason")).viaAgent(rs.getBoolean("via_agent"))
                .at(rs.getObject("at", OffsetDateTime.class))
                .before(snapshot(rs.getString("before_json"))).after(snapshot(rs.getString("after_json")))).list();
    }

    private Object snapshot(String value) {
        return value == null ? null : json.readValue(value, Map.class);
    }
}
