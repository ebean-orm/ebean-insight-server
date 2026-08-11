package org.ebean.monitor.web;

import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared helper turning a dense {@link MetricTimeBucket} list into the pair of
 * "total execution time" / "mean execution time" {@link ChartData} payloads
 * used by the hash and label trend charts on {@code /ux/metric-detail}.
 */
final class BucketCharts {

  static final DateTimeFormatter BUCKET_LABEL_FORMAT =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US).withZone(ZoneOffset.UTC);

  private BucketCharts() {
  }

  record TotalMean(ChartData total, ChartData mean) {
  }

  static TotalMean build(List<MetricTimeBucket> buckets, long bucketMinutes) {
    final List<String> labels = new ArrayList<>(buckets.size());
    final List<Long> totalMs = new ArrayList<>(buckets.size());
    final List<Long> meanMs = new ArrayList<>(buckets.size());
    for (MetricTimeBucket bucket : buckets) {
      labels.add(BUCKET_LABEL_FORMAT.format(bucket.eventTime()));
      final long ms = bucket.total() / 1000L;
      totalMs.add(ms);
      meanMs.add(bucket.count() == 0L ? null : ms / bucket.count());
    }
    final ChartData total = new ChartData(labels,
      timestamps(buckets),
      List.of(new ChartData.ChartDataset("Total (ms)", totalMs, "#4e79a7")), bucketMinutes);
    final ChartData mean = new ChartData(labels,
      timestamps(buckets),
      List.of(new ChartData.ChartDataset("Mean (ms)", meanMs, "#f28e2b")), bucketMinutes);
    return new TotalMean(total, mean);
  }

  static ChartData buildHashStacked(MetricTimeseriesTop timeseries, java.util.Map<String, String> colors) {
    final List<String> labels = timeseries.series().isEmpty()
      ? List.of()
      : timeseries.series().get(0).buckets().stream()
        .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
        .toList();
    final List<ChartData.ChartDataset> datasets = timeseries.series().stream()
      .map(series -> new ChartData.ChartDataset(
        series.group(),
        series.buckets().stream().map(bucket -> bucket.total() / 1000L).toList(),
        colors.getOrDefault(series.group(), Palette.OTHER_COLOR)))
      .toList();
    return new ChartData(labels, timestamps(timeseries), datasets, timeseries.bucketMinutes());
  }

  static ChartData buildHashMean(MetricTimeseriesTop timeseries, java.util.Map<String, String> colors) {
    final List<String> labels = timeseries.series().isEmpty()
      ? List.of()
      : timeseries.series().get(0).buckets().stream()
        .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
        .toList();
    final List<ChartData.ChartDataset> datasets = timeseries.series().stream()
      .map(series -> new ChartData.ChartDataset(
        series.group(),
        series.buckets().stream()
          .map(bucket -> bucket.count() == 0L ? null : (bucket.total() / 1000L) / bucket.count())
          .toList(),
        colors.getOrDefault(series.group(), Palette.OTHER_COLOR)))
      .toList();
    return new ChartData(labels, timestamps(timeseries), datasets, timeseries.bucketMinutes());
  }

  static ChartData buildHashMax(MetricTimeseriesTop timeseries, java.util.Map<String, String> colors) {
    final List<String> labels = timeseries.series().isEmpty()
      ? List.of()
      : timeseries.series().get(0).buckets().stream()
        .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
        .toList();
    final List<ChartData.ChartDataset> datasets = timeseries.series().stream()
      .map(series -> new ChartData.ChartDataset(
        series.group(),
        series.buckets().stream().map(bucket -> bucket.max() / 1000L).toList(),
        colors.getOrDefault(series.group(), Palette.OTHER_COLOR)))
      .toList();
    return new ChartData(labels, timestamps(timeseries), datasets, timeseries.bucketMinutes());
  }

  private static List<Long> timestamps(List<MetricTimeBucket> buckets) {
    return buckets.stream().map(bucket -> bucket.eventTime().toEpochMilli()).toList();
  }

  private static List<Long> timestamps(MetricTimeseriesTop timeseries) {
    return timeseries.series().isEmpty()
      ? List.of()
      : timestamps(timeseries.series().get(0).buckets());
  }
}
