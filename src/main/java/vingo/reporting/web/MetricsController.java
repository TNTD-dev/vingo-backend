package vingo.reporting.web;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.AdminReportsApi;
import vingo.api.model.MetricResult;
import vingo.reporting.MetricQuery;
import vingo.reporting.ReportingService;

/** Prefix /api/v1 do shared ApiPathConfig gắn qua interface sinh từ contract. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class MetricsController implements AdminReportsApi {
    private final ReportingService reporting;

    public MetricsController(ReportingService reporting) { this.reporting = reporting; }

    @Override
    public ResponseEntity<MetricResult> queryMetric(String metric, UUID eventId, UUID showtimeId,
            UUID priceTierId, OffsetDateTime from, OffsetDateTime to, String groupBy) {
        return ResponseEntity.ok(reporting.query(new MetricQuery(metric, eventId, showtimeId, priceTierId, from, to, groupBy)));
    }
}
