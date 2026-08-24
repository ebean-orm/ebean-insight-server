package org.ebean.monitor.web;

import org.junit.jupiter.api.Test;
import org.ebean.monitor.v1.model.MetricTimeBucket;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UIQueryTotalControllerTest {

  @Test
  void metricDetailUrlPreservesAbsoluteRange() {
    final String url = UIQueryTotalController.metricDetailUrl(
      "orders app",
      "prod",
      "custom",
      "Order.findList",
      Instant.parse("2026-08-11T02:00:00Z"),
      Instant.parse("2026-08-11T03:00:00Z"));

    assertThat(url).isEqualTo(
      "/ux/metric-detail?app=orders+app&range=custom&label=Order.findList"
        + "&env=prod&from=2026-08-11T02%3A00%3A00Z&to=2026-08-11T03%3A00%3A00Z");
  }

  @Test
  void metricDetailUrlPreservesTimezone() {
    final String url = UIQueryTotalController.metricDetailUrl(
      "orders app",
      "prod",
      "4h",
      "Order.findList",
      null,
      null,
      "utc");

    assertThat(url).isEqualTo(
      "/ux/metric-detail?app=orders+app&range=4h&label=Order.findList&env=prod&tz=utc");
  }

  @Test
  void queryPlanUrlPreservesAbsoluteRange() {
    final String url = UIQueryTotalController.queryPlanUrl(
      42L,
      "custom",
      Instant.parse("2026-08-11T02:00:00Z"),
      Instant.parse("2026-08-11T03:00:00Z"));

    assertThat(url).isEqualTo(
      "/ux/query-plan?id=42&range=custom"
        + "&from=2026-08-11T02%3A00%3A00Z&to=2026-08-11T03%3A00%3A00Z");
  }

  @Test
  void cpuMillicores_preserveGapsAndIgnoreCounterResets() {
    var buckets = List.of(
      bucket(1, 10_000_000L),
      bucket(1, 70_000_000L),
      bucket(0, 0L),
      bucket(1, 20_000_000L),
      bucket(1, 80_000_000L));

    assertThat(UIQueryTotalController.cpuMillicores(buckets, 1L))
      .containsExactly(null, 1_000L, null, null, 1_000L);
  }

  @Test
  void cpuMillicores_convertCpuTimeToCores() {
    var buckets = List.of(
      bucket(1, 10_000_000L),
      bucket(1, 70_000_000L));

    assertThat(UIQueryTotalController.cpuMillicores(buckets, 1L))
      .containsExactly(null, 1_000L);
  }

  @Test
  void poolSizeValues_preservePeakGaugeTotalAcrossDisplayBuckets() {
    var buckets = List.of(
      bucket(1, 24L, 30L),
      bucket(1, 100L, 120L));

    assertThat(UIQueryTotalController.poolSizeValues(buckets))
      .containsExactly(30L, 120L);
  }

  @Test
  void poolTimingValues_preserveMicrosecondPrecision() {
    var buckets = List.of(
      bucket(1, 375L),
      bucket(1, 1_250L));

    assertThat(UIQueryTotalController.poolTimingValues(buckets))
      .containsExactly(375L, 1_250L);
  }

  @Test
  void dashboardHasDataWhenOnlySystemChartIsPopulated() {
    var chart = new ChartData(
      List.of("08-19 11:00"),
      List.of(1L),
      List.of(new ChartData.ChartDataset("pool", List.of(1L), "#fff")),
      1L);

    assertThat(UIQueryTotalController.hasDashboardData(false, chart)).isTrue();
  }

  @Test
  void dashboardHasNoDataWhenAllChartsAreEmpty() {
    var chart = new ChartData(List.of(), List.of(), List.of(), 0L);

    assertThat(UIQueryTotalController.hasDashboardData(false, chart)).isFalse();
  }

  private static MetricTimeBucket bucket(long count, long total) {
    return bucket(count, total, total);
  }

  private static MetricTimeBucket bucket(long count, long total, long max) {
    return MetricTimeBucket.builder()
      .eventTime(Instant.EPOCH)
      .count(count)
      .total(total)
      .max(max)
      .build();
  }
}
