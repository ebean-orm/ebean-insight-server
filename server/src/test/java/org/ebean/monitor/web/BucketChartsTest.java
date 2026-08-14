package org.ebean.monitor.web;

import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;
import org.ebean.monitor.v1.model.MetricTimeseriesTopSeries;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BucketChartsTest {

  @Test
  void hashChartsIncludeStackedExecutionCounts() {
    final var buckets = List.of(
      bucket("2026-08-14T00:00:00Z", 3L, 9_000L),
      bucket("2026-08-14T00:01:00Z", 5L, 15_000L));
    final var timeseries = MetricTimeseriesTop.builder()
      .app("shop-app")
      .windowMinutes(2L)
      .bucketMinutes(1L)
      .series(List.of(MetricTimeseriesTopSeries.builder()
        .group("a1b2c3")
        .other(false)
        .hashCount(1L)
        .totalMicros(24_000L)
        .buckets(buckets)
        .build()))
      .build();

    final var charts = BucketCharts.buildHashCharts(timeseries, Map.of("a1b2c3", "#123456"));

    assertThat(charts.count().timestamps()).containsExactly(
      Instant.parse("2026-08-14T00:00:00Z").toEpochMilli(),
      Instant.parse("2026-08-14T00:01:00Z").toEpochMilli());
    assertThat(charts.count().datasets()).singleElement().satisfies(dataset -> {
      assertThat(dataset.label()).isEqualTo("a1b2c3");
      assertThat(dataset.data()).containsExactly(3L, 5L);
    });
  }

  private static MetricTimeBucket bucket(String time, long count, long total) {
    return MetricTimeBucket.builder()
      .eventTime(Instant.parse(time))
      .count(count)
      .total(total)
      .max(total)
      .build();
  }
}
