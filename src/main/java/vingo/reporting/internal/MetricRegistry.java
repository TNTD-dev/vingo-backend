package vingo.reporting.internal;

import java.util.Map;

/** Chỉ SQL do module quản lý được ghép vào query; không dùng chuỗi caller làm SQL. */
public final class MetricRegistry {
    public record Definition(String unit, String formula, String source, String timeColumn,
            String numerator, String denominator) {}

    public static final Map<String, Definition> ALL = Map.of(
            "revenue", new Definition("VND", "SUM(quantity * unit_price) of PAID orders; time = paid_at; VND",
                    MetricSources.SALES, "paid_at", "COALESCE(SUM(quantity::numeric * unit_price),0)", null),
            "tickets_sold", new Definition("tickets", "SUM(quantity) of PAID orders; time = paid_at",
                    MetricSources.SALES, "paid_at", "COALESCE(SUM(quantity),0)", null),
            "checkins", new Definition("checkins", "Distinct USED tickets of PAID orders; time = first OK scan",
                    MetricSources.CHECKINS, "checked_in_at", "COUNT(*)", null),
            "fill_rate", new Definition("percent", "100 * SUM(inventory sold) / SUM(capacity); time = starts_at; zero capacity = 0",
                    MetricSources.INVENTORY, "starts_at", "COALESCE(SUM(sold),0)", "COALESCE(SUM(capacity),0)"));

    private MetricRegistry() {}
}
