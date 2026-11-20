package vingo.catalog.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vingo.shared.security.CurrentUser;

@Repository
public class VenueRepository {

    private static final String COLUMNS = """
            id, name, type, location->>'display_address' AS address,
            venue_location_city(location) AS city,
            (SELECT a->>'name' FROM jsonb_array_elements(COALESCE(location->'administrative_area','[]'::jsonb)) a
             WHERE a->>'level'='DISTRICT' LIMIT 1) AS district,
            (location->>'latitude')::numeric AS lat, (location->>'longitude')::numeric AS lng,
            location::text AS location_json, CAST(info AS text) AS info_json, created_at, updated_at, updated_by,
            (SELECT u.full_name FROM users u WHERE u.id=venue.updated_by) AS updated_by_name,
            (SELECT count(*) FROM seat_layout l WHERE l.venue_id=venue.id) AS layout_count,
            (SELECT count(*) FROM seat_layout l WHERE l.venue_id=venue.id AND l.status='PUBLISHED') AS published_layout_count,
            (SELECT count(*) FROM seat_layout l WHERE l.venue_id=venue.id AND l.status='DRAFT') AS draft_layout_count
            """;

    private final JdbcClient jdbc;

    private final JsonMapper json;
    private final CurrentUser currentUser;

    public VenueRepository(JdbcClient jdbc, JsonMapper json, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    public record VenueData(
            String name,
            String type,
            String address,
            String city,
            String district,
            BigDecimal lat,
            BigDecimal lng,
            String infoJson,
            String locationJson
            ) {
        public VenueData(String name, String type, String address, String city, String district,
                BigDecimal lat, BigDecimal lng, String infoJson) {
            this(name, type, address, city, district, lat, lng, infoJson, null);
        }
    }

    public record VenueRow(
            UUID id,
            VenueData data,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID updatedBy, String updatedByName, long layoutCount, long publishedLayoutCount, long draftLayoutCount
            ) {}

    public record VenuePosition(OffsetDateTime createdAt, UUID id) {}

    private static VenueRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        var data = new VenueData(
                rs.getString("name"),
                rs.getString("type"),
                rs.getString("address"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getBigDecimal("lat"),
                rs.getBigDecimal("lng"),
                rs.getString("info_json"), rs.getString("location_json")
        );

        return new VenueRow(
                rs.getObject("id", UUID.class),
                data,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", UUID.class), rs.getString("updated_by_name"),
                rs.getLong("layout_count"), rs.getLong("published_layout_count"), rs.getLong("draft_layout_count")
        );
    }

    public VenueRow insert(VenueData venue) {
        return bindVenue(jdbc.sql("""
                INSERT INTO venue (name, type, location, info, updated_by)
                VALUES (
                    :name, :type, CAST(:location AS jsonb), COALESCE(CAST(:info AS jsonb), '{}'::jsonb), :actor
                )
                RETURNING
                """ + COLUMNS), venue)
                .query(VenueRepository::mapRow)
                .single();
    }

    private JdbcClient.StatementSpec bindVenue(
            JdbcClient.StatementSpec statement, VenueData venue
    ) {
        return statement
                .param("name", venue.name())
                .param("type", venue.type())
                .param("location", locationJson(venue))
                .param("actor", currentUser.find().map(u -> u.id()).orElse(null))
                .param("info", venue.infoJson());
    }

    private String locationJson(VenueData data) {
        if (data.locationJson() != null) return data.locationJson();
        // Tương thích dữ liệu nhập cũ; mọi địa chỉ mới từ API bản đồ truyền JSON đầy đủ.
        var location = new java.util.LinkedHashMap<String, Object>();
        location.put("display_address", data.address());
        location.put("latitude", data.lat());
        location.put("longitude", data.lng());
        location.put("city_code", data.city());
        location.put("source", "LEGACY");
        location.put("administrative_area", data.district() == null ? java.util.List.of()
                : java.util.List.of(java.util.Map.of("level", "DISTRICT", "name", data.district())));
        return json.writeValueAsString(location);
    }

    public List<VenueRow> findActive(
            String query,
            String city,
            VenuePosition after,
            int fetchSize
    ) {
        return jdbc.sql("""
                SELECT
                """ + COLUMNS + """
                FROM venue
                WHERE deleted_at IS NULL
                  AND (
                      CAST(:query AS text) IS NULL
                      OR strpos(lower(name), lower(CAST(:query AS text))) > 0
                  )
                  AND (CAST(:city AS text) IS NULL OR venue_location_city(location) = :city)
                  AND (
                      CAST(:afterCreatedAt AS timestamptz) IS NULL
                      OR (created_at, id) < (
                          CAST(:afterCreatedAt AS timestamptz),
                          CAST(:afterId AS uuid)
                      )
                  )
                ORDER BY created_at DESC, id DESC
                LIMIT :fetchSize
                """)
                .param("query", query)
                .param("city", city)
                .param("afterCreatedAt", after == null ? null : after.createdAt())
                .param("afterId", after == null ? null : after.id())
                .param("fetchSize", fetchSize)
                .query(VenueRepository::mapRow)
                .list();
    }

    public Optional<VenueRow> findActiveByIdForUpdate(UUID venueId) {
        return jdbc.sql("""
                SELECT
                """ + COLUMNS + """
                FROM venue
                WHERE id = :id AND deleted_at IS NULL
                FOR UPDATE
                """)
                .param("id", venueId)
                .query(VenueRepository::mapRow)
                .optional();
    }

    public Optional<VenueRow> findActiveById(UUID venueId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM venue WHERE id=:id AND deleted_at IS NULL")
                .param("id", venueId).query(VenueRepository::mapRow).optional();
    }

    public Optional<VenueRow> update(UUID venueId, VenueData venue, OffsetDateTime now) {
        return bindVenue(jdbc.sql("""
                UPDATE venue
                SET name = :name,
                    type = :type,
                    location = CAST(:location AS jsonb),
                    updated_by = :actor,
                    info = COALESCE(CAST(:info AS jsonb), '{}'::jsonb),
                    updated_at = :now
                WHERE id = :id AND deleted_at IS NULL
                RETURNING
                """ + COLUMNS), venue)
                .param("id", venueId)
                .param("now", now)
                .query(VenueRepository::mapRow)
                .optional();
    }

    public boolean hasUnfinishedScheduledShowtime(UUID venueId, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM showtime s
                    JOIN seat_layout l ON l.id = s.seat_layout_id
                    WHERE l.venue_id = :venueId
                      AND s.status IN ('DRAFT', 'SCHEDULED')
                      AND s.ends_at > :now
                )
                """)
                .param("venueId", venueId)
                .param("now", now)
                .query(Boolean.class)
                .single();
    }

    public boolean markDeleted(UUID venueId, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE venue
                SET deleted_at = :now, updated_at = :now, updated_by = :actor
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("actor", currentUser.find().map(u -> u.id()).orElse(null)).param("id", venueId)
                .param("now", now)
                .update() == 1;
    }

}
