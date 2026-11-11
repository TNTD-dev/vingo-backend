package vingo.reporting.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.reporting.MetricQuery;

@Repository
public class MetricReadModel {
    public record Point(String key, BigDecimal value) {}
    private final JdbcClient jdbc;

    public MetricReadModel(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Ghép SQL chỉ từ registry và nhóm đã được service kiểm tra; bind giá trị
     * lọc thành tham số. Tỉ lệ tính từ tổng tử/mẫu (không trung bình phần trăm),
     * mẫu bằng 0 trả 0. Không tạo bảng/view hoặc ghi dữ liệu nghiệp vụ.
     */
    public List<Point> read(MetricRegistry.Definition definition, MetricQuery query, String group) {
        String bucket = switch (group) {
            case "none" -> "'all'";
            case "day" -> "to_char(" + definition.timeColumn() + " AT TIME ZONE 'UTC','YYYY-MM-DD')";
            case "hour" -> "to_char(" + definition.timeColumn() + " AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:00:00\"Z\"')";
            case "tier" -> "price_tier_id::text";
            case "zone" -> "zone_id::text";
            default -> throw new IllegalArgumentException("Grouping must be validated by ReportingService");
        };
        String sql = "SELECT " + bucket + " AS bucket," + definition.numerator() + " AS numerator,"
                + (definition.denominator() == null ? "1" : definition.denominator()) + " AS denominator FROM (" + definition.source() + ") metric_source"
                + " WHERE true"
                + (query.from() == null ? "" : " AND " + definition.timeColumn() + ">=:from")
                + (query.to() == null ? "" : " AND " + definition.timeColumn() + "<:to")
                + (query.eventId() == null ? "" : " AND event_id=:event_id")
                + (query.showtimeId() == null ? "" : " AND showtime_id=:showtime_id")
                + (query.priceTierId() == null ? "" : " AND price_tier_id=:tier_id")
                + (group.equals("none") ? "" : " GROUP BY 1 ORDER BY 1");
        var statement = jdbc.sql(sql);
        if (query.from() != null) statement.param("from", query.from());
        if (query.to() != null) statement.param("to", query.to());
        if (query.eventId() != null) statement.param("event_id", query.eventId());
        if (query.showtimeId() != null) statement.param("showtime_id", query.showtimeId());
        if (query.priceTierId() != null) statement.param("tier_id", query.priceTierId());
        return statement.query((rs, row) -> {
            BigDecimal numerator = rs.getBigDecimal("numerator");
            BigDecimal denominator = rs.getBigDecimal("denominator");
            BigDecimal value = definition.denominator() == null ? numerator : denominator.signum() == 0 ? BigDecimal.ZERO
                    : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
            return new Point(rs.getString("bucket"), value);
        }).list();
    }
}
