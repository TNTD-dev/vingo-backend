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
            id, name, address, city, district, lat, lng,
            rules, created_at, updated_at,
            (SELECT count(*) FROM seat_map m WHERE m.venue_id = venue.id) AS layout_count,
            (SELECT count(*) FROM venue_import_session i WHERE i.venue_id = venue.id) AS draft_layout_count
            """;

    private final JdbcClient jdbc;

    public VenueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record VenueData(
            String name,
            String address,
            String city,
            String district,
            BigDecimal lat,
            BigDecimal lng,
            String rules
            ) {}

    public record VenueRow(
            UUID id,
            VenueData data,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long layoutCount,
            long draftLayoutCount
            ) {}

    public record VenuePosition(OffsetDateTime createdAt, UUID id) {}

    private static VenueRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        var data = new VenueData(
                rs.getString("name"),
                rs.getString("address"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getBigDecimal("lat"),
                rs.getBigDecimal("lng"),
                rs.getString("rules")
        );

        return new VenueRow(
                rs.getObject("id", UUID.class),
                data,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("layout_count"),
                rs.getLong("draft_layout_count")
        );
    }

    public VenueRow insert(VenueData venue) {
        return bindVenue(jdbc.sql("""
                INSERT INTO venue (name, address, city, district, lat, lng, rules)
                VALUES (:name, :address, :city, :district, :lat, :lng, :rules)
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
                .param("address", venue.address())
                .param("city", venue.city())
                .param("district", venue.district())
                .param("lat", venue.lat())
                .param("lng", venue.lng())
                .param("rules", venue.rules());
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

    public Optional<VenueRow> findActiveById(UUID venueId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM venue WHERE id = :id AND deleted_at IS NULL")
                .param("id", venueId)
                .query(VenueRepository::mapRow)
                .optional();
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
                    address = :address,
                    city = :city,
                    district = :district,
                    lat = :lat,
                    lng = :lng,
                    rules = :rules,
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
                    JOIN event e ON e.id = s.event_id
                    JOIN seat_map m ON m.id = s.seat_map_id
                    WHERE m.venue_id = :venueId
                      AND s.cancelled_at IS NULL
                      AND e.status IN ('DRAFT', 'PUBLISHED')
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

}
