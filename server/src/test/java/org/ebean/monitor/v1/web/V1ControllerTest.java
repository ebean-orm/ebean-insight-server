package org.ebean.monitor.v1.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.rollup.Rollup;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.AppSummary;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@InjectTest
class V1ControllerTest {

  private static final String APP = "v1app";
  private static final String ENV = "v1env";
  private static final String ORM_LABEL = "orm.OrderDao.findOrdersForPublishing";
  private static final String ORM_HASH = "v1ormhash00000000000000000000001";
  private static final String PLAIN_LABEL = "OrderDao.findOrdersForPublishing";
  private static final String PLAIN_HASH = "v1plainhash0000000000000000000001";

  @Inject
  HttpClient httpClient;

  @Inject
  Database database;

  private final Instant eventMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

  @Test
  void v1Endpoints() throws InterruptedException {
    seedMetrics();
    Thread.sleep(500);
    rollup();

    final List<App> apps = httpClient.request().path("v1/apps").GET().asList(App.class).body();
    assertThat(apps).extracting(App::name).contains(APP);

    final AppSummary summary = httpClient.request().path("v1/apps").path(APP).GET().as(AppSummary.class).body();
    assertThat(summary.name()).isEqualTo(APP);
    assertThat(summary.metricCount()).isGreaterThanOrEqualTo(2L);
    assertThat(summary.lastReportAt()).isNotNull();

    final HttpResponse<String> unknownApp = httpClient.request().path("v1/apps/no-such-app-xyz").GET().asString();
    assertThat(unknownApp.statusCode()).isEqualTo(404);

    final List<App> recent = httpClient.request().path("v1/apps").queryParam("activeWithinMinutes", 60_000_000L).GET().asList(App.class).body();
    assertThat(recent).extracting(App::name).contains(APP);

    final HttpResponse<String> bothWindows = httpClient.request().path("v1/apps")
      .queryParam("activeWithinMinutes", 60L).queryParam("activeWithinHours", 1L)
      .GET().asString();
    assertThat(bothWindows.statusCode()).isEqualTo(400);

    final List<Env> envs = httpClient.request().path("v1/envs").GET().asList(Env.class).body();
    assertThat(envs).extracting(Env::name).contains(ENV);

    final List<AppMetric> allMetrics = httpClient.request().path("v1/apps").path(APP).path("metrics").GET().asList(AppMetric.class).body();
    assertThat(allMetrics).extracting(AppMetric::name).contains(ORM_LABEL, PLAIN_LABEL);

    final List<AppMetric> ormOnly = httpClient.request().path("v1/apps").path(APP).path("metrics")
      .queryParam("planCapable", true).GET().asList(AppMetric.class).body();
    assertThat(ormOnly).extracting(AppMetric::name).contains(ORM_LABEL).doesNotContain(PLAIN_LABEL);

    final List<AppMetric> plainOnly = httpClient.request().path("v1/apps").path(APP).path("metrics")
      .queryParam("planCapable", false).GET().asList(AppMetric.class).body();
    assertThat(plainOnly).extracting(AppMetric::name).contains(PLAIN_LABEL).doesNotContain(ORM_LABEL);

    final List<AppMetric> labelFilter = httpClient.request().path("v1/apps").path(APP).path("metrics")
      .queryParam("label", ORM_LABEL).GET().asList(AppMetric.class).body();
    assertThat(labelFilter).extracting(AppMetric::name).containsOnly(ORM_LABEL);

    final HttpResponse<List<AppMetric>> noAppMetrics = httpClient.request().path("v1/apps/no-such-app/metrics")
      .GET().asList(AppMetric.class);
    assertThat(noAppMetrics.statusCode()).isEqualTo(200);
    assertThat(noAppMetrics.body()).isEmpty();

    final List<AppMetric> byLabel = httpClient.request().path("v1/apps").path(APP).path("metrics/by-label").path(ORM_LABEL)
      .GET().asList(AppMetric.class).body();
    assertThat(byLabel).extracting(AppMetric::name).containsOnly(ORM_LABEL);

    final List<AppMetric> byHash = httpClient.request().path("v1/apps").path(APP).path("metrics/by-hash").path(ORM_HASH)
      .GET().asList(AppMetric.class).body();
    assertThat(byHash).extracting(AppMetric::key).containsOnly(ORM_HASH);

    final List<AppMetricStats> stats = httpClient.request().path("v1/apps").path(APP).path("metrics/by-hash").path(ORM_HASH).path("stats")
      .GET().asList(AppMetricStats.class).body();
    assertThat(stats).extracting(AppMetricStats::key).containsOnly(ORM_HASH);
    assertThat(stats).extracting(AppMetricStats::planCapable).containsOnly(true);

    final List<AppMetricStats> top = httpClient.request().path("v1/apps").path(APP).path("metrics/top")
      .queryParam("orderBy", "total").queryParam("limit", 10).GET().asList(AppMetricStats.class).body();
    assertThat(top).extracting(AppMetricStats::app).containsOnly(APP);
    assertThat(top).extracting(AppMetricStats::key).contains(ORM_HASH, PLAIN_HASH);

    final HttpResponse<String> badOrder = httpClient.request().path("v1/apps").path(APP).path("metrics/top")
      .queryParam("orderBy", "garbage").GET().asString();
    assertThat(badOrder.statusCode()).isEqualTo(400);

    final List<AppMetricStats> topOrm = httpClient.request().path("v1/apps").path(APP).path("metrics/top")
      .queryParam("planCapable", true).GET().asList(AppMetricStats.class).body();
    assertThat(topOrm).extracting(AppMetricStats::key).contains(ORM_HASH).doesNotContain(PLAIN_HASH);

    final HttpResponse<String> bothSince = httpClient.request().path("v1/apps").path(APP).path("metrics/top")
      .queryParam("sinceMinutes", 60L).queryParam("sinceHours", 1L).GET().asString();
    assertThat(bothSince.statusCode()).isEqualTo(400);

    final List<MissingPlanMetric> missing = httpClient.request().path("v1/apps").path(APP).path("metrics/missing-plans")
      .GET().asList(MissingPlanMetric.class).body();
    assertThat(missing).extracting(MissingPlanMetric::label).contains(ORM_LABEL).doesNotContain(PLAIN_LABEL);
    assertThat(missing).extracting(MissingPlanMetric::lastCapturedAt).contains((java.time.Instant) null);

    final List<AppMetricStats> globalTop = httpClient.request().path("v1/metrics/top")
      .queryParam("limit", 50).GET().asList(AppMetricStats.class).body();
    assertThat(globalTop).extracting(AppMetricStats::key).contains(ORM_HASH);

    final PendingResponse pending = httpClient.request().path("v1/apps").path(APP).path("plans/by-hash").path(ORM_HASH).path("request")
      .queryParam("env", ENV).POST().as(PendingResponse.class).body();
    assertThat(pending.pending()).isGreaterThanOrEqualTo(1);

    final HttpResponse<String> noCap = httpClient.request().path("v1/apps").path(APP).path("plans/by-hash").path(PLAIN_HASH).path("request")
      .POST().asString();
    assertThat(noCap.statusCode()).isEqualTo(400);

    final HttpResponse<String> noApp = httpClient.request().path("v1/apps/no-such-app/plans/by-hash/" + ORM_HASH + "/request")
      .POST().asString();
    assertThat(noApp.statusCode()).isEqualTo(404);

    seedQueryPlan();
    Thread.sleep(500);

    final List<QueryPlanSummary> appPlans = httpClient.request().path("v1/apps").path(APP).path("plans")
      .GET().asList(QueryPlanSummary.class).body();
    assertThat(appPlans).extracting(QueryPlanSummary::hash).contains(ORM_HASH);

    final List<QueryPlanSummary> byHashPlans = httpClient.request().path("v1/apps").path(APP).path("plans/by-hash").path(ORM_HASH)
      .GET().asList(QueryPlanSummary.class).body();
    assertThat(byHashPlans).extracting(QueryPlanSummary::hash).containsOnly(ORM_HASH);

    final List<QueryPlanSummary> byLabelPlans = httpClient.request().path("v1/apps").path(APP).path("plans/by-label").path(ORM_LABEL)
      .GET().asList(QueryPlanSummary.class).body();
    assertThat(byLabelPlans).extracting(QueryPlanSummary::hash).contains(ORM_HASH);

    assertThat(httpClient.request().path("v1/apps").path(APP).path("plans").queryParam("env", ENV).GET().asList(QueryPlanSummary.class).body())
      .extracting(QueryPlanSummary::hash).contains(ORM_HASH);
    assertThat(httpClient.request().path("v1/apps").path(APP).path("plans").queryParam("env", "no-such-env").GET().asList(QueryPlanSummary.class).body())
      .isEmpty();

    final List<QueryPlanSummary> globalPlans = httpClient.request().path("v1/plans")
      .queryParam("hash", ORM_HASH).GET().asList(QueryPlanSummary.class).body();
    assertThat(globalPlans).extracting(QueryPlanSummary::hash).containsOnly(ORM_HASH);

    final long planId = appPlans.getFirst().id();
    final QueryPlan one = httpClient.request().path("v1/plans").path(String.valueOf(planId))
      .GET().as(QueryPlan.class).body();
    assertThat(one.id()).isEqualTo(planId);
    assertThat(one.hash()).isEqualTo(ORM_HASH);
    assertThat(one.sql()).isNotBlank();

    final HttpResponse<String> noPlan = httpClient.request().path("v1/plans/9999999").GET().asString();
    assertThat(noPlan.statusCode()).isEqualTo(404);

    final List<MissingPlanMetric> missingAfter = httpClient.request().path("v1/apps").path(APP).path("metrics/missing-plans")
      .GET().asList(MissingPlanMetric.class).body();
    assertThat(missingAfter).extracting(MissingPlanMetric::label).doesNotContain(ORM_LABEL);
  }

  private void rollup() {
    new Rollup(database, eventMinute).rollup();
  }

  private void seedMetrics() {
    final String payload = """
      {
        "eventTime": %d,
        "appName": "%s",
        "environment": "%s",
        "dbs": [
          {
            "db": "db",
            "metrics": [
              {"name": "%s", "count": 5, "total": 1000, "mean": 200, "max": 400, "hash": "%s", "loc": "x.java:1", "sql": "select 1"},
              {"name": "%s", "count": 3, "total": 90, "mean": 30, "max": 50, "hash": "%s", "loc": "x.java:2", "sql": "select 2"}
            ]
          }
        ]
      }
      """.formatted(eventMinute.toEpochMilli(), APP, ENV,
        ORM_LABEL, ORM_HASH,
        PLAIN_LABEL, PLAIN_HASH);
    final HttpResponse<String> res = httpClient.request()
      .path("api/ingest/metrics")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(payload)
      .POST()
      .asString();
    assertThat(res.statusCode()).isEqualTo(204);
  }

  private void seedQueryPlan() {
    final String payload = """
      {
        "environment": "%s",
        "appName": "%s",
        "plans": [
          {
            "label": "%s",
            "queryTimeMicros": 200,
            "captureCount": 5,
            "captureMicros": 400,
            "hash": "%s",
            "sql": "select 1 from t",
            "bind": "[1]",
            "plan": "Seq Scan",
            "whenCaptured": "2025-01-01T01:02:05Z"
          }
        ]
      }
      """.formatted(ENV, APP, ORM_LABEL, ORM_HASH);
    final HttpResponse<String> res = httpClient.request()
      .path("api/ingest/plans")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(payload)
      .POST()
      .asString();
    assertThat(res.statusCode()).isEqualTo(204);
  }
}
