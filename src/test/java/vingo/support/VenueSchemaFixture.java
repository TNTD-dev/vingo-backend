package vingo.support;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Dựng hạ tầng v2 cho test booking/search; test import dùng SVG qua HTTP riêng. */
public final class VenueSchemaFixture {
    private VenueSchemaFixture() {}
    public static UUID draft(JdbcClient jdbc,UUID venue) {
        UUID overview=jdbc.sql("""
                INSERT INTO venue_overview(venue_id,name,svg_content,view_box_width,view_box_height)
                VALUES(:v,'Test overview','<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000"><rect id="GATE__G1" x="900" y="900" width="10" height="10"/></svg>',1000,1000) RETURNING id
                """).param("v",venue).query(UUID.class).single();
        jdbc.sql("INSERT INTO venue_gate(overview_id,code,name,svg_id) VALUES(:id,'G1','G1','GATE__G1')").param("id",overview).update();
        UUID layout=jdbc.sql("INSERT INTO seat_layout(venue_id,overview_id,layout_type,name) VALUES(:v,:o,'CONCERT','Test layout') RETURNING id")
                .param("v",venue).param("o",overview).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO seat_layout_svg(seat_layout_id,schema_version,svg_content,view_box_x,view_box_y,view_box_width,view_box_height)
                VALUES(:id,2,'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000"/>',0,0,1000,1000)
                """).param("id",layout).update();
        return layout;
    }
    public static void publish(JdbcClient jdbc,UUID layout) {
        UUID overview=jdbc.sql("SELECT overview_id FROM seat_layout WHERE id=:id").param("id",layout).query(UUID.class).single();
        jdbc.sql("UPDATE venue_overview SET validation_hash=catalog_overview_hash(id),validation_engine='fixture',validated_at=now() WHERE id=:id")
                .param("id",overview).update();
        jdbc.sql("UPDATE venue_overview SET status='PUBLISHED',published_at=now() WHERE id=:id").param("id",overview).update();
        jdbc.sql("UPDATE seat_layout SET validation_hash=catalog_layout_hash(id),validation_engine='fixture',validated_at=now() WHERE id=:id")
                .param("id",layout).update();
        jdbc.sql("UPDATE seat_layout SET status='PUBLISHED',published_at=now() WHERE id=:id").param("id",layout).update();
    }
}
