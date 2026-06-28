package org.ebean.monitor.v1.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.domain.query.QDTimedEntry;
import org.ebean.monitor.rollup.Rollup;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.model.TopGroup;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the v2 (name + tags) ranked {@code top} grouping levels and the
 * gauge value path. Uses a v2 ingest envelope so metrics carry canonical family
 * names and a delimited tag string.
 */
@InjectTest
class TopGroupQueryTest {

  private static final String APP = "topapp";
  private static final String ENV = "topenv";

  @Inject
  HttpClient httpClient;

  @Inject
  Database database;

  private final Instant eventMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

  @Test
  void topGroupingLevels() {
    seedV2();
    awaitTimedEntries(APP, "ebean.query", 3);
    new Rollup(database, eventMinute).rollup();

    final MetricsApi metrics = httpClient.create(MetricsApi.class);

    // by=name (Level 1): family rollup over timers. ebean.query spans the two
    // query hashes; the gauge family is ranked separately via orderBy=value.
    final List<TopGroup> byName = metrics.topAppMetrics(APP, "name", null, null, null, null, "total", null, null, 50, null, null);
    assertThat(byName).extracting(TopGroup::group).contains("ebean.query");
    final TopGroup queryFamily = byName.stream().filter(g -> g.group().equals("ebean.query")).findFirst().orElseThrow();
    assertThat(queryFamily.hashCount()).isEqualTo(3L);
    assertThat(queryFamily.name()).isEqualTo("ebean.query");

    // by=label (default): sum across the two hashes sharing label Customer.findList.
    final List<TopGroup> byLabel = metrics.topAppMetrics(APP, null, "ebean.query", null, null, null, "total", null, null, 50, null, null);
    assertThat(byLabel).extracting(TopGroup::group).containsExactlyInAnyOrder("Customer.findList", "Order.findList");
    final TopGroup custLabel = byLabel.stream().filter(g -> g.group().equals("Customer.findList")).findFirst().orElseThrow();
    assertThat(custLabel.label()).isEqualTo("Customer.findList");
    assertThat(custLabel.hashCount()).isEqualTo(2L);
    // two Customer hashes: total = 1000 + 500
    assertThat(custLabel.totalMicros()).isEqualTo(1500L);

    // by=type (tag): Customer query (2 hashes) and Order query (1 hash).
    final List<TopGroup> byType = metrics.topAppMetrics(APP, "type", "ebean.query", null, null, null, "total", null, null, 50, null, null);
    assertThat(byType).extracting(TopGroup::group).containsExactlyInAnyOrder("Customer", "Order");
    final TopGroup custType = byType.stream().filter(g -> g.group().equals("Customer")).findFirst().orElseThrow();
    assertThat(custType.hashCount()).isEqualTo(2L);

    // by=hash with a label filter: scope per-hash rows to one label tag (drill
    // from the default by=label view into its individual queries, with timing).
    final List<TopGroup> byHashForLabel = metrics.topAppMetrics(APP, "hash", null, "Customer.findList", null, null, "total", null, null, 50, null, null);
    assertThat(byHashForLabel).hasSize(2);
    assertThat(byHashForLabel).extracting(TopGroup::label).containsOnly("Customer.findList");
    assertThat(byHashForLabel).extracting(TopGroup::totalMicros).containsExactlyInAnyOrder(1000L, 500L);

    // strict grouping: datasource.* carries no label tag -> empty list.
    final List<TopGroup> strict = metrics.topAppMetrics(APP, "label", "datasource.pool.size", null, null, null, "value", null, null, 50, null, null);
    assertThat(strict).isEmpty();

    // gauge value path: rank datasource pool size by peak value, grouped by type.
    final List<TopGroup> gauge = metrics.topAppMetrics(APP, "type", "datasource.pool.size", null, null, null, "value", null, null, 50, null, null);
    assertThat(gauge).extracting(TopGroup::group).contains("active");
    final TopGroup active = gauge.stream().filter(g -> g.group().equals("active")).findFirst().orElseThrow();
    assertThat(active.value()).isEqualTo(8.0d);
    assertThat(active.totalMicros()).isNull();
  }

  private void seedV2() {
    final String payload = """
      {
        "v": 2,
        "eventTime": %d,
        "appName": "%s",
        "environment": "%s",
        "metrics": [
          {"name": "datasource.pool.size", "tags": "db:main,type:active", "value": 8, "hash": "tphash00000000000000000000000gauge"}
        ],
        "dbs": [
          {
            "db": "db",
            "metrics": [
              {"name": "ebean.query", "tags": "kind:orm,label:Customer.findList,type:Customer", "count": 5, "total": 1000, "mean": 200, "max": 400, "hash": "tphash000000000000000000000000001", "loc": "x.java:1", "sql": "select 1"},
              {"name": "ebean.query", "tags": "kind:orm,label:Customer.findList,type:Customer", "count": 2, "total": 500, "mean": 250, "max": 300, "hash": "tphash000000000000000000000000002", "loc": "x.java:2", "sql": "select 2"},
              {"name": "ebean.query", "tags": "kind:orm,label:Order.findList,type:Order", "count": 3, "total": 90, "mean": 30, "max": 50, "hash": "tphash000000000000000000000000003", "loc": "x.java:3", "sql": "select 3"}
            ]
          }
        ]
      }
      """.formatted(eventMinute.toEpochMilli(), APP, ENV);
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
