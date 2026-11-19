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
import org.ebean.monitor.v1.web.V1QueryService.MetricSeriesTableRow;
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

  @Inject
  V1QueryService queryService;

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

    final HttpResponse<String> tableResponse = httpClient.request()
      .path("ux/top/table?app=" + APP + "&env=" + ENV + "&range=1h&family=dml")
      .GET()
      .asString();
    assertThat(tableResponse.statusCode()).isEqualTo(200);
    assertThat(tableResponse.body())
      .contains("\"label\":\"Customer.insert\"")
      .contains("\"label\":\"Order.update\"");
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

  @Test
  @Order(6)
  void seriesTableIncludesAllLabelsAndSupportsSearchAndPagination() {
    final String tableApp = APP + "-table-" + System.nanoTime();
    final Instant tableEventMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    final Instant tablePrevMinute = tableEventMinute.minus(1, ChronoUnit.MINUTES);
    seedMinute(tableApp, ENV, tablePrevMinute, """
      {"name": "ebean.query", "tags": "label:X", "count": 1, "total": 1000, "mean": 1000, "max": 1000, "hash": "tableserieshash00000000000000000001"},
      {"name": "ebean.query", "tags": "label:Y", "count": 1, "total": 800, "mean": 800, "max": 800, "hash": "tableserieshash00000000000000000002"},
      {"name": "ebean.query", "tags": "label:Z", "count": 1, "total": 100, "mean": 100, "max": 100, "hash": "tableserieshash00000000000000000003"},
      {"name": "ebean.query", "tags": "label:W", "count": 1, "total": 50, "mean": 50, "max": 50, "hash": "tableserieshash00000000000000000004"},
      {"name": "app.component", "tags": "label:OrderService.placeOrder", "count": 1, "total": 600, "mean": 600, "max": 600, "hash": "tablecomponentshash000000000000000001"},
      {"name": "app.component", "tags": "label:BillingClient.charge", "count": 1, "total": 300, "mean": 300, "max": 300, "hash": "tablecomponentshash000000000000000002"}
      """);
    seedMinute(tableApp, ENV, tableEventMinute, """
      {"name": "ebean.query", "tags": "label:X", "count": 1, "total": 1200, "mean": 1200, "max": 1200, "hash": "tableserieshash00000000000000000001"},
      {"name": "ebean.query", "tags": "label:Y", "count": 1, "total": 900, "mean": 900, "max": 900, "hash": "tableserieshash00000000000000000002"},
      {"name": "ebean.query", "tags": "", "count": 1, "total": 40, "mean": 40, "max": 40, "hash": "tableserieshash00000000000000000005"},
      {"name": "app.component", "tags": "label:OrderService.placeOrder", "count": 1, "total": 500, "mean": 500, "max": 500, "hash": "tablecomponentshash000000000000000001"},
      {"name": "app.component", "tags": "label:BillingClient.charge", "count": 1, "total": 400, "mean": 400, "max": 400, "hash": "tablecomponentshash000000000000000002"}
      """);
    awaitTimedEntries(tableApp, "ebean.query", 7);
    awaitTimedEntries(tableApp, "app.component", 4);
    new Rollup(database, tablePrevMinute).rollup();
    new Rollup(database, tableEventMinute).rollup();
    final Instant from = tablePrevMinute.minus(1, ChronoUnit.MINUTES);
    final Instant to = tableEventMinute.plus(1, ChronoUnit.MINUTES);

    final var firstPage = queryService.getMetricSeriesTable(
      tableApp, "ebean.query", ENV, from, to, null, "total", true, 0, 2);

    assertThat(firstPage.totalRows()).isEqualTo(5L);
    assertThat(firstPage.rows()).extracting(MetricSeriesTableRow::label)
      .containsExactly("X", "Y");
    assertThat(firstPage.maxTotalMicros()).isEqualTo(2200L);
    assertThat(firstPage.maxCount()).isEqualTo(2L);

    final var countSorted = queryService.getMetricSeriesTable(
      tableApp, "ebean.query", ENV, from, to, null, "count", true, 0, 2);
    assertThat(countSorted.rows()).extracting(MetricSeriesTableRow::label)
      .containsExactly("X", "Y");

    final var fourHourPage = queryService.getMetricSeriesTable(
      tableApp, "ebean.query", 240L, ENV, null, "total", true, 0, 50);
    assertThat(fourHourPage.totalRows()).isEqualTo(5L);
    assertThat(fourHourPage.maxTotalMicros()).isEqualTo(2200L);

    final var lastPage = queryService.getMetricSeriesTable(
      tableApp, "ebean.query", ENV, from, to, null, "total", true, 2, 2);
    assertThat(lastPage.rows()).extracting(MetricSeriesTableRow::label)
      .containsExactly((String) null);

    final var search = queryService.getMetricSeriesTable(
      tableApp, "ebean.query", ENV, from, to, "z", "total", true, 0, 2);
    assertThat(search.totalRows()).isEqualTo(1L);
    assertThat(search.rows()).extracting(MetricSeriesTableRow::label)
      .containsExactly("Z");

    final HttpResponse<String> response = httpClient.request()
      .path("ux/top/table?app=" + tableApp + "&env=" + ENV
        + "&range=1h&family=query&search=z&sort=total&direction=desc&page=0")
      .GET()
      .asString();
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
      .contains("\"totalRows\":1")
      .contains("\"label\":\"Z\"")
      .contains("\"detailUrl\":\"/ux/metric-detail?");

    final HttpResponse<String> componentResponse = httpClient.request()
      .path("ux/top/table?app=" + tableApp + "&env=" + ENV
        + "&range=1h&family=app-component&sort=total&direction=desc&page=0")
      .GET()
      .asString();
    assertThat(componentResponse.statusCode()).isEqualTo(200);
    assertThat(componentResponse.body())
      .contains("\"label\":\"OrderService.placeOrder\"")
      .contains("\"label\":\"BillingClient.charge\"");
  }

  private static long bucketTotal(MetricTimeseriesTopSeries series, Instant bucketTime) {
    return series.buckets().stream()
      .filter(b -> b.eventTime().equals(bucketTime))
      .mapToLong(MetricTimeBucket::total)
      .findFirst()
      .orElseThrow(() -> new AssertionError("No bucket at " + bucketTime + " in series " + series.group()));
  }

  private void seedMinute(Instant minute, String metricsJson) {
    seedMinute(APP, ENV, minute, metricsJson);
  }

  private void seedMinute(String app, String environment, Instant minute, String metricsJson) {
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
      """.formatted(minute.toEpochMilli(), app, environment, metricsJson);
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
