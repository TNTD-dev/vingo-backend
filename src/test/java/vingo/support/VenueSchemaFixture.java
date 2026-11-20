package vingo.support;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Dựng hạ tầng v2 cho test booking/search; test import dùng SVG qua HTTP riêng. */
public final class VenueSchemaFixture {
    private VenueSchemaFixture() {}
    public static UUID draft(JdbcClient jdbc, UUID venue) {
        UUID venueMap = jdbc.sql("""
                INSERT INTO venue_map (venue_id, version, svg)
                VALUES (:v, COALESCE((SELECT max(version) + 1 FROM venue_map WHERE venue_id = :v), 1),
                        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000"><rect id="GATE__G1" x="900" y="900" width="10" height="10"/></svg>')
                RETURNING id
                """).param("v", venue).query(UUID.class).single();
        return jdbc.sql("""
                INSERT INTO seat_map (venue_id, venue_map_id, version, name, layout_type, svg)
                VALUES (:v, :m, COALESCE((SELECT max(version) + 1 FROM seat_map WHERE venue_id = :v), 1),
                        'Test layout', 'CONCERT', '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000"/>')
                RETURNING id
                """)
                .param("v", venue).param("m", venueMap).query(UUID.class).single();
    }

    public static void publish(JdbcClient jdbc, UUID layout) {
        // Sơ đồ v2 bất biến ngay khi tạo, không cần bước publish.
    }
}
