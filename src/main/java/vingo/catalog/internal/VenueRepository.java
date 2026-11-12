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

@Repository
public class VenueRepository {

    private static final String COLUMNS = """
            id, name, type, address, city, district, lat, lng,
            CAST(info AS text) AS info_json, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public VenueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record VenueData(
            String name,
            String type,
            String address,
            String city,
            String district,
            BigDecimal lat,
            BigDecimal lng,
            String infoJson
    ) {}

    public record VenueRow(
            UUID id,
            VenueData data,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
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
                rs.getString("info_json")
        );

        return new VenueRow(
                rs.getObject("id", UUID.class),
                data,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
    public VenueRow insert(VenueData venue) {
        return bindVenue(jdbc.sql("""
                INSERT INTO venue (name, type, address, city, district, lat, lng, info)
                VALUES (
                    :name, :type, :address, :city, :district, :lat, :lng,
                    COALESCE(CAST(:info AS jsonb), '{}'::jsonb)
                )
                RETURNING
                """ + COLUMNS), venue)
                .query(VenueRepository::mapRow)
                .single();
    }

    private static JdbcClient.StatementSpec bindVenue(
            JdbcClient.StatementSpec statement, VenueData venue
    ) {
        return statement
                .param("name", venue.name())
                .param("type", venue.type())
                .param("address", venue.address())
                .param("city", venue.city())
                .param("district", venue.district())
                .param("lat", venue.lat())
                .param("lng", venue.lng())
                .param("info", venue.infoJson());
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
                  AND (CAST(:city AS text) IS NULL OR city = :city)
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

    public Optional<VenueRow> update(UUID venueId, VenueData venue, OffsetDateTime now) {
        return bindVenue(jdbc.sql("""
                UPDATE venue
                SET name = :name,
                    type = :type,
                    address = :address,
                    city = :city,
                    district = :district,
                    lat = :lat,
                    lng = :lng,
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
                    JOIN hall h ON h.id = s.hall_id
                    WHERE h.venue_id = :venueId
                      AND s.status = 'SCHEDULED'
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
                SET deleted_at = :now, updated_at = :now
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", venueId)
                .param("now", now)
                .update() == 1;
    }
    
    public record HallRow(UUID id, UUID venueId, String name) {}

    public Optional<HallRow> insertHall(UUID venueId, String name) {
        return jdbc.sql("""
                INSERT INTO hall (venue_id, name)
                VALUES (:venueId, :name)
                ON CONFLICT (venue_id, name) DO NOTHING
                RETURNING id, venue_id, name
                """)
                .param("venueId", venueId)
                .param("name", name)
                .query((rs, rowNumber) -> new HallRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("venue_id", UUID.class),
                        rs.getString("name")
                ))
                .optional();
    }
}