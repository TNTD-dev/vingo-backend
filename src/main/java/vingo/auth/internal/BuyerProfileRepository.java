package vingo.auth.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vingo.api.model.BuyerProfile;

@Repository
public class BuyerProfileRepository {
    private final JdbcClient jdbc;
    public BuyerProfileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<BuyerProfile> find(UUID userId) {
        return jdbc.sql("SELECT full_name, phone, email FROM buyer_profile WHERE user_id = :id")
                .param("id", userId).query((rs, row) -> new BuyerProfile(rs.getString("full_name"), rs.getString("phone"), rs.getString("email"))).optional();
    }
    public void save(UUID userId, BuyerProfile profile, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO buyer_profile(user_id, full_name, phone, email, updated_at)
                VALUES (:id, :name, :phone, :email, :now)
                ON CONFLICT (user_id) DO UPDATE SET full_name = excluded.full_name,
                    phone = excluded.phone, email = excluded.email, updated_at = excluded.updated_at
                """)
                .param("id", userId).param("name", profile.getFullName()).param("phone", profile.getPhone())
                .param("email", profile.getEmail()).param("now", now).update();
    }
}
