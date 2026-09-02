package org.ebean.monitor.v1.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.domain.query.QDTimedEntry;
import org.ebean.monitor.rollup.Rollup;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;
import org.ebean.monitor.v1.model.MetricTimeseriesTopSeries;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@code /v1/apps/{app}/metrics/top/timeseries}: top-N label ranking,
 * dense per-bucket zero-fill, and the synthetic "Other" remainder series (which
 * also picks up metrics with no {@code label} tag).
 *
 * <p>Ordered: the orderBy=value rejection is only reachable once the app
 * exists (an unknown app short-circuits before parameter validation, matching
 * the existing {@code topAppMetrics} convention), so the seeding test runs first.
 */
@InjectTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TopTimeseriesQueryTest {

  private static final String APP = "tsapp";
  private static final String ENV = "tsenv";

  @Inject
  HttpClient httpClient;

  @Inject
  Database database;

  private final Instant eventMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
  private final Instant prevMinute = eventMinute.minus(1, ChronoUnit.MINUTES);

  @Test
  @Order(1)
  void topSeriesWithOtherRemainder() {
    seedMinute(prevMinute, """
      {"name": "ebean.query", "tags": "kind:orm,label:X,type:Foo", "count": 1, "total": 1000, "mean": 1000, "max": 1000, "hash": "tstshash0000000000000000000000X", "loc": "x.java:1", "sql": "select x"},
      {"name": "ebean.query", "tags": "kind:orm,label:Y,type:Foo", "count": 1, "total": 800, "mean": 800, "max": 800, "hash": "tstshash0000000000000000000000Y", "loc": "x.java:2", "sql": "select y"},
      {"name": "ebean.query", "tags": "kind:orm,label:Z,type:Foo", "count": 1, "total": 100, "mean": 100, "max": 100, "hash": "tstshash0000000000000000000000Z", "loc": "x.java:3", "sql": "select z"},
      {"name": "ebean.query", "tags": "kind:orm,label:W,type:Foo", "count": 1, "total": 50, "mean": 50, "max": 50, "hash": "tstshash0000000000000000000000W", "loc": "x.java:4", "sql": "select w"}
      """);
    seedMinute(eventMinute, """
      {"name": "ebean.query", "tags": "kind:orm,label:X,type:Foo", "count": 1, "total": 1200, "mean": 1200, "max": 1200, "hash": "tstshash0000000000000000000000X", "loc": "x.java:1", "sql": "select x"},
      {"name": "ebean.query", "tags": "kind:orm,label:Y,type:Foo", "count": 1, "total": 900, "mean": 900, "max": 900, "hash": "tstshash0000000000000000000000Y", "loc": "x.java:2", "sql": "select y"},
      {"name": "ebean.query", "tags": "kind:orm,type:Foo", "count": 1, "total": 40, "mean": 40, "max": 40, "hash": "tstshash0000000000000000000000N", "loc": "x.java:5", "sql": "select n"}
      """);
    awaitTimedEntries(APP, "ebean.query", 7);
    new Rollup(database, prevMinute).rollup();
    new Rollup(database, eventMinute).rollup();

    final MetricsApi metrics = httpClient.create(MetricsApi.class);

    final MetricTimeseriesTop result =
      metrics.topAppMetricsTimeseries(APP, null, null, null, "total", null, null, 2, null, ENV);

    assertThat(result.app()).isEqualTo(APP);
    assertThat(result.bucketMinutes()).isEqualTo(1L);
    assertThat(result.series()).extracting(MetricTimeseriesTopSeries::group)
      .containsExactly("X", "Y", "Other");

    final MetricTimeseriesTopSeries seriesX = result.series().get(0);
    assertThat(seriesX.other()).isFalse();
    assertThat(seriesX.hashCount()).isEqualTo(1L);
    assertThat(seriesX.totalMicros()).isEqualTo(2200L);
    assertThat(bucketTotal(seriesX, prevMinute)).isEqualTo(1000L);
    assertThat(bucketTotal(seriesX, eventMinute)).isEqualTo(1200L);

    final MetricTimeseriesTopSeries seriesY = result.series().get(1);
    assertThat(seriesY.other()).isFalse();
    assertThat(seriesY.hashCount()).isEqualTo(1L);
    assertThat(seriesY.totalMicros()).isEqualTo(1700L);
    assertThat(bucketTotal(seriesY, prevMinute)).isEqualTo(800L);
    assertThat(bucketTotal(seriesY, eventMinute)).isEqualTo(900L);

    // Other = Z + W (prevMinute only) + N (no label tag, eventMinute only).
    final MetricTimeseriesTopSeries other = result.series().get(2);
    assertThat(other.other()).isTrue();
    assertThat(other.hashCount()).isEqualTo(3L);
    assertThat(other.totalMicros()).isEqualTo(190L);
    assertThat(bucketTotal(other, prevMinute)).isEqualTo(150L);
    assertThat(bucketTotal(other, eventMinute)).isEqualTo(40L);

    // Dense zero-fill: a bucket outside the seeded minutes is present with zero total for every series.
    final Instant farBucket = eventMinute.minus(30, ChronoUnit.MINUTES);
    assertThat(bucketTotal(seriesX, farBucket)).isEqualTo(0L);
    assertThat(bucketTotal(other, farBucket)).isEqualTo(0L);
  }

  @Test
  @Order(2)
  void orderByValue_isRejected() {
    final MetricsApi metrics = httpClient.create(MetricsApi.class);
    assertThatThrownBy(() ->
      metrics.topAppMetricsTimeseries(APP, null, null, null, "value", null, null, null, null, null))
      .hasMessageContaining("400");
  }

  @Test
  @Order(3)
  void unknownApp_returnsEmptySeries() {
    final MetricsApi metrics = httpClient.create(MetricsApi.class);
    final MetricTimeseriesTop result =
      metrics.topAppMetricsTimeseries("no-such-app", null, null, null, null, null, null, null, null, null);
    assertThat(result.series()).isEmpty();
  }

  @Test
  @Order(4)
  void dmlFamily_usesLabelSeries() {
    seedMinute(eventMinute, """
      {"name": "ebean.dml", "tags": "label:Customer.insert", "count": 3, "total": 900, "mean": 300, "max": 400, "hash": "dmlhash000000000000000000000001", "loc": "x.java:6"},
      {"name": "ebean.dml", "tags": "label:Order.update", "count": 2, "total": 500, "mean": 250, "max": 300, "hash": "dmlhash000000000000000000000002", "loc": "x.java:7"}
      """);
    awaitTimedEntries(APP, "ebean.dml", 2);
    new Rollup(database, eventMinute).rollup();

    final MetricsApi metrics = httpClient.create(MetricsApi.class);
    final MetricTimeseriesTop result = metrics.topAppMetricsTimeseries(
      APP, "ebean.dml", null, null, "total", null, null, 10, null, ENV);

    assertThat(result.series()).extracting(MetricTimeseriesTopSeries::group)
      .contains("Customer.insert", "Order.update");
    assertThat(result.series()).allSatisfy(series ->
      assertThat(series.other()).isFalse());
  }

  @Test
  @Order(5)
  void appComponentFamily_usesLabelSeries() {
    seedMinute(eventMinute, """
      {"name": "app.component", "tags": "label:OrderService.placeOrder", "count": 4, "total": 1200, "mean": 300, "max": 450, "hash": "componenthash00000000000000000001", "loc": "x.java:8"},
      {"name": "app.component", "tags": "label:BillingClient.charge", "count": 2, "total": 900, "mean": 450, "max": 600, "hash": "componenthash00000000000000000002", "loc": "x.java:9"}
      """);
    awaitTimedEntries(APP, "app.component", 2);
    new Rollup(database, eventMinute).rollup();

    final MetricsApi metrics = httpClient.create(MetricsApi.class);
    final MetricTimeseriesTop result = metrics.topAppMetricsTimeseries(
      APP, "app.component", null, null, "total", null, null, 10, null, ENV);

    assertThat(result.series()).extracting(MetricTimeseriesTopSeries::group)
      .contains("OrderService.placeOrder", "BillingClient.charge");
    assertThat(result.series()).allSatisfy(series ->
      assertThat(series.other()).isFalse());
  }

  private static long bucketTotal(MetricTimeseriesTopSeries series, Instant bucketTime) {
    return series.buckets().stream()
      .filter(b -> b.eventTime().equals(bucketTime))
      .mapToLong(MetricTimeBucket::total)
      .findFirst()
      .orElseThrow(() -> new AssertionError("No bucket at " + bucketTime + " in series " + series.group()));
  }

  private void seedMinute(Instant minute, String metricsJson) {
    final String payload = """
      {
        "v": 2,
        "eventTime": %d,
        "appName": "%s",
        "environment": "%s",
        "dbs": [
          {
            "db": "db",
            "metrics": [
              %s
            ]
          }
        ]
      }
      """.formatted(minute.toEpochMilli(), APP, ENV, metricsJson);
    final HttpResponse<String> res = httpClient.request()
      .path("api/ingest/metrics")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(payload)
      .POST()
      .asString();
    assertThat(res.statusCode()).isEqualTo(204);
  }

  /**
   * Wait until at least {@code expected} timed entries exist for the app + metric
   * name. Ingest is async (background queue consumer), so polling is deterministic
   * where a fixed sleep races the consumer under load.
   */
  private void awaitTimedEntries(String app, String metricName, int expected) {
    for (int i = 0; i < 200; i++) {
      final int count = new QDTimedEntry(database)
        .metric.app.name.eq(app)
        .metric.name.eq(metricName)
        .findCount();
      if (count >= expected) {
        return;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("Timed out waiting for " + expected
      + " timed_entry rows for app '" + app + "' metric '" + metricName + "'");
  }
}
