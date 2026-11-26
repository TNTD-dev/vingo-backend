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
                        VALUES (:actor, :action, :entity, :id, :reason, :viaAgent, :at,
                                CAST(:before AS jsonb), CAST(:after AS jsonb)) RETURNING id
                        """)
                .param("viaAgent", vingo.shared.security.AgentRequest.isViaAgent())
                .param("actor", actorId).param("action", action).param("entity", entity).param("id", entityId)
                .param("reason", reason, Types.VARCHAR).param("at", at)
                .param("before", before == null ? null : json.writeValueAsString(before), Types.VARCHAR)
                .param("after", after == null ? null : json.writeValueAsString(after), Types.VARCHAR)
                .query(UUID.class).single();
    }

    /** Chỉ cập nhật kết quả nhật ký request; không sửa snapshot của audit nghiệp vụ. */
    public void completeActivity(UUID id, Map<String, Object> details) {
        int updated = jdbc.sql("""
                UPDATE audit_log SET after = CAST(:details AS jsonb)
                WHERE id = :id AND action = 'ADMIN_ACTIVITY'
                """).param("id", id).param("details", json.writeValueAsString(details)).update();
        if (updated != 1) {
            throw new IllegalStateException("Không tìm thấy nhật ký hoạt động admin");
        }
    }

    /** Đọc keyset at/id giảm dần và giải mã snapshot JSONB thành object. */
    public List<AuditLog> find(String entity, UUID entityId, OffsetDateTime beforeAt, UUID beforeId, int limit) {
        return find(entity,entityId,beforeAt,beforeId,limit,null,null,null,null,null,false);
    }

    public List<AuditLog> find(String entity, UUID entityId, OffsetDateTime beforeAt, UUID beforeId, int limit,
            UUID actor, String action, OffsetDateTime from, OffsetDateTime to, String outcome, boolean activityOnly) {
        var query = jdbc.sql("""
                        SELECT id, actor_id, action, entity, entity_id, reason, via_agent, at,
                               (SELECT u.full_name FROM user_account u WHERE u.id=audit_log.actor_id) AS actor_name,
                               before::text AS before_json, after::text AS after_json FROM audit_log WHERE true
                        """
                        + (entity == null ? "" : " AND entity = :entity")
                        + (entityId == null ? "" : " AND entity_id = :entityId")
                        + (beforeAt == null ? "" : " AND (at, id) < (:at, :id)")
                        + (actor == null ? "" : " AND actor_id=:actor")
                        + (action == null ? "" : " AND action=:action")
                        + (from == null ? "" : " AND at>=:from")
                        + (to == null ? "" : " AND at<:to")
                        + (outcome == null ? "" : " AND after->>'outcome'=:outcome")
                        + (activityOnly ? " AND action='ADMIN_ACTIVITY' AND after->>'outcome'='SUCCEEDED' AND after->>'method' NOT IN ('GET','HEAD','OPTIONS')" : "")
                        + " ORDER BY at DESC, id DESC LIMIT :limit")
                .param("limit", limit);
        if(actor!=null) query.param("actor",actor);
        if(action!=null) query.param("action",action);
        if(from!=null) query.param("from",from);
        if(to!=null) query.param("to",to);
        if(outcome!=null) query.param("outcome",outcome);
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
                .actorName(rs.getString("actor_name")).actorId(rs.getObject("actor_id", UUID.class)).action(AuditLog.ActionEnum.fromValue(rs.getString("action")))
                .entity(rs.getString("entity")).entityId(rs.getObject("entity_id", UUID.class))
                .reason(rs.getString("reason")).viaAgent(rs.getBoolean("via_agent"))
                .at(rs.getObject("at", OffsetDateTime.class))
                .before(snapshot(rs.getString("before_json"))).after(snapshot(rs.getString("after_json")))).list();
    }

    private Object snapshot(String value) {
        return value == null ? null : json.readValue(value, Map.class);
    }
}
