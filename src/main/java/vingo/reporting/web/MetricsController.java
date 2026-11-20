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
import vingo.reporting.DashboardService;

/** Prefix /api/v1 do shared ApiPathConfig gắn qua interface sinh từ contract. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class MetricsController implements AdminReportsApi {
    private final ReportingService reporting;
    private final DashboardService dashboard;

    public MetricsController(ReportingService reporting, DashboardService dashboard) {
        this.reporting = reporting;
        this.dashboard = dashboard;
    }

    @Override
    public ResponseEntity<vingo.api.model.AdminDashboard> getAdminDashboard(
            OffsetDateTime from, OffsetDateTime to, String groupBy) {
        return ResponseEntity.ok(dashboard.read(from, to, groupBy));
    }

    @Override
    public ResponseEntity<MetricResult> queryMetric(String metric, UUID eventId, UUID showtimeId,
            UUID priceTierId, OffsetDateTime from, OffsetDateTime to, String groupBy) {
        return ResponseEntity.ok(reporting.query(new MetricQuery(metric, eventId, showtimeId, priceTierId, from, to, groupBy)));
    }
}
