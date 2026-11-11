package vingo.reporting;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import vingo.api.model.MetricResult;
import vingo.api.model.MetricResultSeriesInner;
import vingo.reporting.internal.MetricReadModel;
import vingo.reporting.internal.MetricRegistry;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
public class ReportingService {
    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);
    private static final Set<String> GROUPS = Set.of("none", "day", "hour", "tier", "zone");
    private final MetricReadModel readModel;
    private final Clock clock;

    public ReportingService(MetricReadModel readModel, Clock clock) {
        this.readModel = readModel;
        this.clock = clock;
    }

    /**
     * Tính một trong bốn metric cho ADMIN trên cùng snapshot dữ liệu chỉ đọc.
     * Khoảng thời gian là [from,to); nhóm theo UTC. Trả value khi không chia
     * nhóm hoặc series khi chia nhóm, kèm bộ lọc/công thức để giải thích kết quả.
     * sales_velocity chưa được triển khai vì còn mâu thuẫn contract/kế hoạch.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 8)
    public MetricResult query(MetricQuery query) {
        var definition = MetricRegistry.ALL.get(query.metric());
        String group = query.groupBy() == null ? "none" : query.groupBy();
        if (definition == null || !GROUPS.contains(group)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Supported metrics: revenue, tickets_sold, fill_rate, checkins. "
                            + "group_by must be none/day/hour/tier/zone");
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "from must be earlier than to");
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("group_by", group);
        if (query.from() != null) filters.put("from", query.from().withOffsetSameInstant(ZoneOffset.UTC).toString());
        if (query.to() != null) filters.put("to", query.to().withOffsetSameInstant(ZoneOffset.UTC).toString());
        if (query.eventId() != null) filters.put("event_id", query.eventId().toString());
        if (query.showtimeId() != null) filters.put("showtime_id", query.showtimeId().toString());
        if (query.priceTierId() != null) filters.put("price_tier_id", query.priceTierId().toString());
        var points = readModel.read(definition, query, group);
        var result = new MetricResult().metric(query.metric()).unit(MetricResult.UnitEnum.fromValue(definition.unit()))
                .computedAt(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).filters(filters)
                .definition(definition.formula() + "; bounds [from,to), groups UTC; current-state data");
        if (group.equals("none")) {
            result.value(points.getFirst().value());
        } else {
            result.series(points.stream().map(point -> new MetricResultSeriesInner().key(point.key()).value(point.value())).toList());
        }
        log.atInfo().addKeyValue("metric", query.metric()).addKeyValue("group_by", group).log("Report computed");
        return result;
    }
}
