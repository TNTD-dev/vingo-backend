package vingo.reporting;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vingo.api.model.AdminDashboard;
import vingo.api.model.AdminDashboardKpis;
import vingo.api.model.MetricResult;
import vingo.reporting.internal.DashboardReadRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class DashboardService {
    private final ReportingService metrics;
    private final DashboardReadRepository reads;
    private final Clock clock;
    public DashboardService(ReportingService metrics,DashboardReadRepository reads,Clock clock) {
        this.metrics=metrics;this.reads=reads;this.clock=clock;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=8)
    public AdminDashboard read(OffsetDateTime from,OffsetDateTime to,String group) {
        var now=OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        to=to==null?now:to;
        from=from==null?to.minusDays(30):from;
        group=group==null?"day":group;
        var duration=Duration.between(from,to);
        if(duration.isZero()||duration.isNegative()||duration.compareTo(Duration.ofDays(366))>0
                || !(group.equals("day")||group.equals("month")))
            throw new DomainException(ErrorCode.VALIDATION_ERROR,"Khoảng thời gian từ 0 đến 366 ngày; nhóm day hoặc month");
        var tiers=metric("tickets_sold",from,to,"tier");
        var names=reads.tierNames();
        tiers.getSeries().forEach(point -> point.label(names.get(point.getKey())));
        return new AdminDashboard().from(from).to(to).computedAt(now).timezone("Asia/Ho_Chi_Minh")
                .current(kpis(from,to)).previous(kpis(from.minus(duration),from)).onSaleShowtimes(reads.onSale(now))
                .revenue(metric("revenue",from,to,group)).tickets(metric("tickets_sold",from,to,group))
                .tiers(tiers).upcoming(reads.upcoming(now)).venues(reads.venues(from,to));
    }

    private AdminDashboardKpis kpis(OffsetDateTime from,OffsetDateTime to) {
        return new AdminDashboardKpis().revenue(metric("revenue",from,to,"none").getValue())
                .ticketsSold(metric("tickets_sold",from,to,"none").getValue()).newUsers(reads.newUsers(from,to));
    }
    private MetricResult metric(String name,OffsetDateTime from,OffsetDateTime to,String group) {
        return metrics.query(new MetricQuery(name,null,null,null,from,to,group));
    }
}
