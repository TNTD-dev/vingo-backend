package vingo.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

import vingo.api.model.MetricResult;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class ReportingIntegrationTest extends IntegrationTest {
    private static final String EVENT = "0197f100-0000-7000-8000-000000000201";
    private static final String PATH = "/api/v1/admin/reports/metrics";
    private final DataSource dataSource;
    private String admin;

    @Autowired
    ReportingIntegrationTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @BeforeEach
    void fixture() {
        new ResourceDatabasePopulator(new ClassPathResource("vingo/reporting-fixture.sql")).execute(dataSource);
        admin = tokenFor(Role.ADMIN);
    }

    @Test
    void metrics_returnFourApprovedValues_fromActualSchemaAndIdempotentFixture() {
        assertThat(metric("revenue", "none").getValue()).isEqualByComparingTo("1400000");
        assertThat(metric("tickets_sold", "none").getValue()).isEqualByComparingTo("4");
        assertThat(metric("fill_rate", "none").getValue()).isEqualByComparingTo("33.33");
        assertThat(metric("checkins", "none").getValue()).isEqualByComparingTo("1");
    }

    /** Kiểm chính xác từng nhóm trên hai ngày/giờ; fill rate dùng tổng sức chứa làm mẫu. */
    @Test
    void groups_returnContractSeries_andWeightedRatesRatherThanAveragePercentages() {
        assertRevenueGroups("day", "2026-11-01", "2026-11-02");
        assertRevenueGroups("hour", "2026-11-01T10:00:00Z", "2026-11-02T12:00:00Z");
        assertRevenueGroups("tier", "0197f100-0000-7000-8000-000000000301", "0197f100-0000-7000-8000-000000000302");
        assertRevenueGroups("zone", "0197f100-0000-7000-8000-000000000131", "0197f100-0000-7000-8000-000000000132");
        var rate = metric("fill_rate", "zone");
        assertThat(rate.getSeries()).hasSize(2);
        assertThat(rate.getSeries().getFirst().getValue()).isEqualByComparingTo("100");
        assertThat(rate.getSeries().getLast().getValue()).isEqualByComparingTo("20");
        assertThat(metric("fill_rate", "none").getValue()).isEqualByComparingTo("33.33");
    }

    @Test
    void bounds_areOptionalAndHalfOpen_andFiltersUseActualContractNames() {
        client.get().uri(PATH + "?metric=revenue&event_id=" + EVENT
                        + "&from=2026-11-01T10:00:00Z&to=2026-11-01T10:00:01Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.value").isEqualTo(1000000)
                .jsonPath("$.computed_at").exists().jsonPath("$.definition").exists()
                .jsonPath("$.filters.event_id").isEqualTo(EVENT)
                .jsonPath("$.total").doesNotExist().jsonPath("$.generated_at").doesNotExist();
        assertValue("&to=2026-11-01T10:00:00Z", "0");
        assertValue("&from=2026-11-01T10:00:01Z", "400000");
        assertValue("&from=2026-11-02T12:00:00Z&to=2026-11-02T12:00:01Z", "400000");
        assertValue("&from=2026-11-02T12:00:01Z", "0");
        assertValue("&price_tier_id=0197f100-0000-7000-8000-000000000302", "400000");
        assertValue("&showtime_id=" + UUID.randomUUID(), "0");
    }

    @Test
    void missingData_returnsZeroAndEmptySeries_withoutDivisionByZero() {
        client.get().uri(PATH + "?metric=fill_rate&event_id=" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.value").isEqualTo(0);
        client.get().uri(PATH + "?metric=checkins&group_by=day&event_id=" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.series").isEmpty();
    }

    /** Đầu vào sai phải dùng lỗi chung; sales_velocity còn chờ duyệt, không tự thêm công thức. */
    @Test
    void invalidQueries_returnSharedValidationError_withoutAddingUnapprovedMetric() {
        for (String query : new String[]{"metric=sales_velocity", "metric=order_conversion_rate", "metric=revenue&group_by=week",
                "metric=revenue&from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z", "metric=revenue&event_id=invalid", "group_by=day"}) {
            client.get().uri(PATH + "?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                    .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void access_usesSharedJwtRolesAndBasePath() {
        client.get().uri(PATH + "?metric=revenue").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client.get().uri(PATH + "?metric=revenue").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Role.BUYER))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.get().uri("/api/v1/api/v1/admin/reports/metrics?metric=revenue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isNotFound();
    }

    private MetricResult metric(String name, String group) {
        return client.get().uri(PATH + "?metric=" + name + "&group_by=" + group + "&event_id=" + EVENT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isOk()
                .expectBody(MetricResult.class).returnResult().getResponseBody();
    }

    /** Không cho test xanh nếu query gom tất cả thành một nhóm hoặc gán sai khóa/giá trị. */
    private void assertRevenueGroups(String group, String firstKey, String secondKey) {
        var report = metric("revenue", group);
        assertThat(report.getValue()).isNull();
        assertThat(report.getSeries()).hasSize(2);
        assertThat(report.getSeries().getFirst().getKey()).isEqualTo(firstKey);
        assertThat(report.getSeries().getFirst().getValue()).isEqualByComparingTo("1000000");
        assertThat(report.getSeries().getLast().getKey()).isEqualTo(secondKey);
        assertThat(report.getSeries().getLast().getValue()).isEqualByComparingTo("400000");
        assertThat(report.getSeries().stream().map(point -> point.getValue()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1400000");
    }

    private void assertValue(String extra, String expected) {
        var response = client.get().uri(PATH + "?metric=revenue&event_id=" + EVENT + extra)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isOk()
                .expectBody(MetricResult.class).returnResult().getResponseBody();
        assertThat(response.getValue()).isEqualByComparingTo(expected);
    }
}
