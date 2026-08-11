package org.ebean.monitor.web;

import io.avaje.jsonb.Json;

import java.util.List;

/**
 * Minimal Chart.js {@code data} payload shape (stacked bar chart), serialized
 * to JSON and embedded in the {@code query-total} page for the client-side
 * chart to consume. Kept separate from the API's {@code MetricTimeseriesTop}
 * model since this is presentation-only shaping (values pre-converted to
 * milliseconds, one dataset per series).
 */
@Json
record ChartData(List<String> labels, List<Long> timestamps, List<ChartDataset> datasets, long bucketMinutes) {

  @Json
  record ChartDataset(String label, List<Long> data, String backgroundColor) {
  }
}
