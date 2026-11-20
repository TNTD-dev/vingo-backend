package vingo.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.OverviewArea;
import vingo.api.model.OverviewGate;
import vingo.api.model.SeatLayoutBounds;
import vingo.api.model.VenueImportDraft;
import vingo.api.model.VenueImportResult;
import vingo.api.model.VenueOverview;

/** Lưu nháp phiên nhập sơ đồ; chỉ INSERT venue_map/seat_map/zone/seat khi hoàn tất. */
@Repository
public class VenueImportRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SeatLayoutSvgParser parser;

    public VenueImportRepository(JdbcClient jdbc, JsonMapper json, SeatLayoutSvgParser parser) {
        this.jdbc = jdbc;
        this.json = json;
        this.parser = parser;
    }

    public record FileSource(UUID sessionId, UUID id, String filename, String role, String svg, int size, String checksum) {}

    public record Snapshot(
            UUID id,
            UUID ownerId,
            UUID venueId,
            VenueImportDraft draft,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long revision,
            String status,
            VenueImportResult result) {
        public OffsetDateTime expiresAt() {
            return updatedAt.plusDays("DRAFT".equals(status) ? 30 : 7);
        }
    }

    private static final String SNAPSHOT = "SELECT id, owner_id, venue_id, draft, created_at, updated_at, revision, status, result FROM venue_import_session ";

    private Snapshot snapshot(ResultSet rs, int row) throws SQLException {
        String result = rs.getString("result");
        return new Snapshot(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getObject("venue_id", UUID.class), json.readValue(rs.getString("draft"), VenueImportDraft.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("revision"), rs.getString("status"),
                result == null ? null : json.readValue(result, VenueImportResult.class));
    }

    public UUID create(UUID owner, UUID venue, VenueImportDraft draft, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO venue_import_session(owner_id, venue_id, draft, created_at, updated_at)
                VALUES (:owner, :venue, CAST(:draft AS jsonb), :now, :now)
                RETURNING id
                """)
                .param("owner", owner)
                .param("venue", venue)
                .param("draft", json.writeValueAsString(draft))
                .param("now", now)
                .query(UUID.class)
                .single();
    }

    public Optional<Snapshot> find(UUID id, UUID owner, boolean lock) {
        return jdbc.sql(SNAPSHOT + "WHERE id = :id AND owner_id = :owner" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("owner", owner).query(this::snapshot)
                .optional();
    }

    public List<Snapshot> list(UUID owner, UUID after, int limit, OffsetDateTime now) {
        return jdbc.sql(SNAPSHOT + """
                WHERE owner_id = :owner AND status = 'DRAFT'
                  AND updated_at > :cutoff
                  AND (CAST(:after AS uuid) IS NULL OR id < :after)
                ORDER BY id DESC LIMIT :limit
                """)
                .param("owner", owner)
                .param("cutoff", now.minusDays(30))
                .param("after", after)
                .param("limit", limit)
                .query(this::snapshot)
                .list();
    }

    public List<FileSource> files(UUID id) {
        return files(List.of(id), true);
    }

    public List<FileSource> files(List<UUID> ids, boolean includeSvg) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT session_id, id, filename, role, octet_length(svg) AS size,
                       encode(sha256(convert_to(svg, 'UTF8')), 'hex') AS checksum, %s AS content
                FROM venue_import_file WHERE session_id IN (:ids) ORDER BY id
                """.formatted(includeSvg ? "svg" : "NULL"))
                .param("ids", ids)
                .query((rs, n) -> new FileSource(rs.getObject("session_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("filename"), rs.getString("role"), rs.getString("content"),
                        rs.getInt("size"), rs.getString("checksum")))
                .list();
    }

    public void insertFile(UUID session, String filename, String role, String safeSvg) {
        jdbc.sql("""
                INSERT INTO venue_import_file(session_id, filename, role, svg)
                VALUES (:session, :filename, :role, :svg)
                """)
                .param("session", session)
                .param("filename", filename)
                .param("role", role)
                .param("svg", safeSvg)
                .update();
    }

    public boolean deleteFile(UUID session, UUID file) {
        return jdbc.sql("DELETE FROM venue_import_file WHERE id = :id AND session_id = :session")
                .param("id", file)
                .param("session", session)
                .update() == 1;
    }

    public void patch(UUID id, VenueImportDraft draft, OffsetDateTime now) {
        jdbc.sql("UPDATE venue_import_session SET draft = CAST(:draft AS jsonb), revision = revision + 1, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("draft", json.writeValueAsString(draft))
                .param("now", now)
                .update();
    }

    public void close(UUID id, String status, VenueImportResult result, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE venue_import_session SET status = :status, result = CAST(:result AS jsonb),
                    updated_at = :now, revision = revision + 1 WHERE id = :id
                """).param("id", id).param("status", status).param("now", now)
                .param("result", result == null ? null : json.writeValueAsString(result)).update();
    }

    public boolean lockVenue(UUID id) {
        return jdbc.sql("SELECT id FROM venue WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public boolean activeVenue(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM venue WHERE id = :id AND deleted_at IS NULL)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public Optional<VenueOverview> overview(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT m.id, m.venue_id, m.version, m.svg, m.created_at
                FROM venue_map m
                JOIN venue v ON v.id = m.venue_id
                WHERE m.id = :id AND v.deleted_at IS NULL
                """ + (lock ? " FOR UPDATE OF m" : ""))
                .param("id", id)
                .query((rs, n) -> mapOverview(
                        rs.getObject("id", UUID.class),
                        rs.getObject("venue_id", UUID.class),
                        rs.getLong("version"),
                        rs.getString("svg"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    public List<UUID> overviews(UUID venue, UUID after, int limit) {
        return jdbc.sql("""
                SELECT id FROM venue_map
                WHERE venue_id = :venue
                  AND (CAST(:after AS uuid) IS NULL OR id < :after)
                ORDER BY id DESC LIMIT :limit
                """)
                .param("venue", venue)
                .param("after", after)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public UUID insertOverview(UUID venue, String svg) {
        return jdbc.sql("""
                INSERT INTO venue_map(venue_id, version, svg)
                VALUES (:venue, (SELECT COALESCE(MAX(version), 0) + 1 FROM venue_map WHERE venue_id = :venue), :svg)
                RETURNING id
                """)
                .param("venue", venue)
                .param("svg", svg)
                .query(UUID.class)
                .single();
    }

    public UUID insertLayout(UUID venue, UUID venueMapId, String type, String name, VenueSvgProcessor.Bundle bundle,
                             SeatLayoutRepository layouts) {
        var bounds = bundle.layout().viewBox();
        UUID id = jdbc.sql("""
                INSERT INTO seat_map(venue_id, venue_map_id, version, name, layout_type, svg, view_box)
                VALUES (
                    :venue,
                    :venueMapId,
                    (SELECT COALESCE(MAX(version), 0) + 1 FROM seat_map WHERE venue_id = :venue),
                    :name,
                    :type,
                    :svg, CAST(:bounds AS numeric[])
                )
                RETURNING id
                """)
                .param("venue", venue)
                .param("venueMapId", venueMapId)
                .param("name", name)
                .param("type", type)
                .param("bounds", bounds == null ? null : new SqlArrayValue(
                        "numeric", bounds.x(), bounds.y(), bounds.width(), bounds.height()))
                .param("svg", bundle.layout().svgContent())
                .query(UUID.class)
                .single();

        for (var z : bundle.layout().zones()) {
            UUID zoneId = layouts.insertZone(id, z);
            if (!z.seats().isEmpty()) {
                layouts.insertSeats(id, zoneId, z.seats());
            }
        }
        return id;
    }

    public int cleanup(OffsetDateTime now, int batch) {
        return jdbc.sql("""
                DELETE FROM venue_import_session WHERE id IN (
                    SELECT id FROM venue_import_session
                    WHERE (status = 'DRAFT' AND updated_at <= :cutoff)
                       OR (status <> 'DRAFT' AND updated_at <= :closedCutoff)
                    ORDER BY updated_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batch
                )
                """)
                .param("cutoff", now.minusDays(30))
                .param("closedCutoff", now.minusDays(7))
                .param("batch", batch)
                .update();
    }

    private VenueOverview mapOverview(UUID id, UUID venueId, long version, String svg, OffsetDateTime createdAt) {
        var overview = new VenueOverview()
                .id(id)
                .venueId(venueId)
                .version(version)
                .svgContent(svg)
                .createdAt(createdAt)
                .gates(List.of())
                .areas(List.of());
        try {
            var parsed = parser.parse(svg.getBytes(StandardCharsets.UTF_8));
            if (parsed.viewBox() != null) {
                overview.viewBox(new SeatLayoutBounds(
                        parsed.viewBox().x(), parsed.viewBox().y(),
                        parsed.viewBox().width(), parsed.viewBox().height()));
            }
        } catch (RuntimeException ignored) {
            // Giữ viewBox null nếu SVG nền không có khung zone chuẩn.
        }
        return overview;
    }
}
