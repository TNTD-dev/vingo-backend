package vingo.auth.internal;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GoogleAuthorizationRequestRepository {

    private final JdbcClient jdbc;

    /** Nhận JDBC client dùng để truy cập kho trạng thái OAuth2. */
    GoogleAuthorizationRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Dọn bản ghi hết hạn và lưu yêu cầu OAuth2 mới. */
    public void save(String stateHash, String browserHash, String requestJson,
                     OffsetDateTime now, OffsetDateTime expiresAt) {
        jdbc.sql("DELETE FROM oauth_login_request WHERE expires_at <= :now").param("now", now).update();
        jdbc.sql("""
                        INSERT INTO oauth_login_request (state_hash, browser_hash, request_json, expires_at)
                        VALUES (:state, :browser, :json, :expires)
                        """)
                .param("state", stateHash)
                .param("browser", browserHash)
                .param("json", requestJson)
                .param("expires", expiresAt)
                .update();
    }

    /** Đọc yêu cầu còn hạn của trình duyệt mà chưa tiêu thụ state. */
    public Optional<String> find(String stateHash, String browserHash, OffsetDateTime now) {
        return query("""
                SELECT request_json FROM oauth_login_request
                WHERE state_hash = :state AND browser_hash = :browser AND expires_at > :now
                """, stateHash, browserHash, now);
    }

    /** Lấy và xoá state còn hạn trong cùng một câu lệnh. */
    public Optional<String> consume(String stateHash, String browserHash, OffsetDateTime now) {
        return query("""
                DELETE FROM oauth_login_request
                WHERE state_hash = :state AND browser_hash = :browser AND expires_at > :now
                RETURNING request_json
                """, stateHash, browserHash, now);
    }

    /** Truy vấn dữ liệu yêu cầu theo state, cookie trình duyệt và thời hạn. */
    private Optional<String> query(String sql, String stateHash, String browserHash, OffsetDateTime now) {
        return jdbc.sql(sql).param("state", stateHash).param("browser", browserHash).param("now", now)
                .query(String.class).optional();
    }
}
