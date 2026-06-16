package org.ebean.monitor.v1.web;

import io.avaje.http.client.HttpClient;
import io.avaje.http.client.HttpException;
import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.rollup.Rollup;
import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.AppSummary;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.PendingPlan;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.model.TopGroup;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@InjectTest
class V1ControllerTest {

  private static final String APP = "v1app";
  private static final String ENV = "v1env";
  private static final String ORM_LABEL = "orm.OrderDao.findOrdersForPublishing";
  private static final String ORM_HASH = "v1ormhash00000000000000000000001";
  private static final String PLAIN_LABEL = "OrderDao.findOrdersForPublishing";
  private static final String PLAIN_HASH = "v1plainhash0000000000000000000001";
  // plan-capable metric whose only execution is outside the cost window
  private static final String STALE_LABEL = "orm.OrderDao.findStaleNeverExecutedInWindow";
  private static final String STALE_HASH = "v1stalehash0000000000000000000001";

  @Inject
  HttpClient httpClient;

  @Inject
  Database database;

  private final Instant eventMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
  // two hours ago: outside the default 60-minute missing-plans cost window
  private final Instant staleMinute = eventMinute.minus(120, ChronoUnit.MINUTES);

  @Test
  void v1Endpoints() throws InterruptedException {
    seedMetrics();
    seedStaleMetric();
    Thread.sleep(500);
    rollup();
    rollupStale();

    final AppsApi appsApi = httpClient.create(AppsApi.class);
    final EnvsApi envsApi = httpClient.create(EnvsApi.class);
    final MetricsApi metricsApi = httpClient.create(MetricsApi.class);
    final PlansApi plansApi = httpClient.create(PlansApi.class);

    final List<App> apps = appsApi.listApps(null, null);
    assertThat(apps).extracting(App::name).contains(APP);

    final AppSummary summary = appsApi.getApp(APP);
    assertThat(summary.name()).isEqualTo(APP);
    assertThat(summary.metricCount()).isGreaterThanOrEqualTo(2L);
    assertThat(summary.lastReportAt()).isNotNull();

    assertThatThrownBy(() -> appsApi.getApp("no-such-app-xyz"))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(404));

    final List<App> recent = appsApi.listApps(60_000_000L, null);
    assertThat(recent).extracting(App::name).contains(APP);

    assertThatThrownBy(() -> appsApi.listApps(60L, 1L))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(400));

    final List<Env> envs = envsApi.listEnvs();
    assertThat(envs).extracting(Env::name).contains(ENV);

    final List<AppMetric> allMetrics = metricsApi.listAppMetrics(APP, null, null, null, null, null, null);
    assertThat(allMetrics).extracting(AppMetric::name).contains(ORM_LABEL, PLAIN_LABEL);

    final List<AppMetric> ormOnly = metricsApi.listAppMetrics(APP, null, null, null, null, true, null);
    assertThat(ormOnly).extracting(AppMetric::name).contains(ORM_LABEL).doesNotContain(PLAIN_LABEL);

    final List<AppMetric> plainOnly = metricsApi.listAppMetrics(APP, null, null, null, null, false, null);
    assertThat(plainOnly).extracting(AppMetric::name).contains(PLAIN_LABEL).doesNotContain(ORM_LABEL);

    // v1-style seed carries no tags, so the family `name` is the discriminator
    // (the `label` param now filters on the tags['label'] tag).
    final List<AppMetric> labelFilter = metricsApi.listAppMetrics(APP, ORM_LABEL, null, null, null, null, null);
    assertThat(labelFilter).extracting(AppMetric::name).containsOnly(ORM_LABEL);

    assertThat(metricsApi.listAppMetrics("no-such-app", null, null, null, null, null, null)).isEmpty();

    final List<AppMetric> byHash = metricsApi.getMetricByHash(APP, ORM_HASH);
    assertThat(byHash).extracting(AppMetric::key).containsOnly(ORM_HASH);

    final List<AppMetricStats> stats = metricsApi.getMetricStatsByHash(APP, ORM_HASH, null, null, null);
    assertThat(stats).extracting(AppMetricStats::key).containsOnly(ORM_HASH);
    assertThat(stats).extracting(AppMetricStats::planCapable).containsOnly(true);

    final List<AppMetricStats> statsEnv = metricsApi.getMetricStatsByHash(APP, ORM_HASH, null, null, ENV);
    assertThat(statsEnv).extracting(AppMetricStats::key).containsOnly(ORM_HASH);
    assertThat(statsEnv.getFirst().count()).isEqualTo(stats.getFirst().count());

    final List<AppMetricStats> statsNoEnv = metricsApi.getMetricStatsByHash(APP, ORM_HASH, null, null, "no-such-env");
    assertThat(statsNoEnv).isEmpty();

    final MetricTimeseries ts = metricsApi.getMetricTimeseries(APP, ORM_HASH, null, null, null);
    assertThat(ts.app()).isEqualTo(APP);
    assertThat(ts.hash()).isEqualTo(ORM_HASH);
    assertThat(ts.bucketMinutes()).isEqualTo(1L);
    assertThat(ts.buckets()).isNotEmpty();
    // Dense series: the whole 60-minute window is returned (one 1-minute bucket
    // each), empty minutes filled with explicit zeros so the time axis stays
    // continuous. The seed only populates one minute, so there is a positive
    // bucket alongside many zero buckets.
    assertThat(ts.buckets()).hasSizeGreaterThanOrEqualTo(60);
    assertThat(ts.buckets()).anySatisfy(b -> assertThat(b.count()).isPositive());
    assertThat(ts.buckets()).anySatisfy(b -> assertThat(b.count()).isZero());
    assertThat(ts.buckets()).allSatisfy(b -> assertThat(b.count()).isGreaterThanOrEqualTo(0L));
    // Buckets are strictly time-ordered and aligned to the bucket boundary.
    assertThat(ts.buckets()).extracting(MetricTimeBucket::eventTime).isSorted();
    assertThat(ts.buckets()).allSatisfy(b ->
      assertThat(b.eventTime().getEpochSecond() % 60L).isZero());
    final long tsCalls = ts.buckets().stream().mapToLong(MetricTimeBucket::count).sum();
    assertThat(tsCalls).isEqualTo(stats.getFirst().count());

    final MetricTimeseries tsEnv = metricsApi.getMetricTimeseries(APP, ORM_HASH, null, null, ENV);
    assertThat(tsEnv.buckets()).isNotEmpty();
    assertThat(tsEnv.buckets()).anySatisfy(b -> assertThat(b.count()).isPositive());

    final MetricTimeseries tsNoEnv = metricsApi.getMetricTimeseries(APP, ORM_HASH, null, null, "no-such-env");
    assertThat(tsNoEnv.buckets()).isEmpty();

    final MetricTimeseries tsNoHash = metricsApi.getMetricTimeseries(APP, "no-such-hash", null, null, null);
    assertThat(tsNoHash.buckets()).isEmpty();

    // by=hash → Level 3 (per-metric rows), the discriminator the v1-style
    // tagless seed supports; key is present on each TopGroup row.
    final List<TopGroup> top = metricsApi.topAppMetrics(APP, "hash", null, null, null, null, "total", null, null, 10, null, null);
    assertThat(top).extracting(TopGroup::app).containsOnly(APP);
    assertThat(top).extracting(TopGroup::key).contains(ORM_HASH, PLAIN_HASH);

    final List<TopGroup> topEnv = metricsApi.topAppMetrics(APP, "hash", null, null, null, null, "total", null, null, 10, null, ENV);
    assertThat(topEnv).extracting(TopGroup::key).contains(ORM_HASH, PLAIN_HASH);

    assertThat(metricsApi.topAppMetrics(APP, "hash", null, null, null, null, "total", null, null, 10, null, "no-such-env")).isEmpty();

    assertThatThrownBy(() -> metricsApi.topAppMetrics(APP, "hash", null, null, null, null, "garbage", null, null, null, null, null))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(400));

    final List<TopGroup> topOrm = metricsApi.topAppMetrics(APP, "hash", null, null, null, null, null, null, null, null, true, null);
    assertThat(topOrm).extracting(TopGroup::key).contains(ORM_HASH).doesNotContain(PLAIN_HASH);

    assertThatThrownBy(() -> metricsApi.topAppMetrics(APP, "hash", null, null, null, null, null, 60L, 1L, null, null, null))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(400));

    final List<MissingPlanMetric> missing = metricsApi.listMissingPlans(APP, null, null, null, null, null, null, null);
    assertThat(missing).extracting(MissingPlanMetric::label).contains(ORM_LABEL).doesNotContain(PLAIN_LABEL);
    // a plan-capable metric with no executions in the cost window is excluded:
    // a plan cannot be captured for a query that never runs in the window.
    assertThat(missing).extracting(MissingPlanMetric::label).doesNotContain(STALE_LABEL);
    assertThat(missing).extracting(MissingPlanMetric::lastCapturedAt).contains((java.time.Instant) null);
    assertThat(missing).allSatisfy(m -> {
      assertThat(m.windowMinutes()).isGreaterThan(0L);
      assertThat(m.totalMicros()).isGreaterThanOrEqualTo(0L);
    });

    // env-scoped cost ranking: the matching env still surfaces the metric,
    // an unknown env short-circuits to no rows (parity with top).
    assertThat(metricsApi.listMissingPlans(APP, null, null, null, null, null, null, ENV))
        .extracting(MissingPlanMetric::label).contains(ORM_LABEL);
    assertThat(metricsApi.listMissingPlans(APP, null, null, null, null, null, null, "no-such-env")).isEmpty();

    final List<MissingPlanMetric> missingGlobal = metricsApi.topMissingPlans("total", null, null, null, null, 50, null);
    assertThat(missingGlobal).extracting(MissingPlanMetric::label).contains(ORM_LABEL).doesNotContain(PLAIN_LABEL);
    assertThat(missingGlobal).extracting(MissingPlanMetric::label).doesNotContain(STALE_LABEL);
    assertThat(metricsApi.topMissingPlans("total", null, null, null, null, 50, "no-such-env")).isEmpty();

    final List<TopGroup> globalTop = metricsApi.topMetrics("hash", null, null, null, null, null, null, null, 50, null, null, null);
    assertThat(globalTop).extracting(TopGroup::key).contains(ORM_HASH);

    final List<TopGroup> globalTopEnv = metricsApi.topMetrics("hash", null, null, null, null, null, null, null, 50, null, ENV, null);
    assertThat(globalTopEnv).extracting(TopGroup::key).contains(ORM_HASH);
    assertThat(metricsApi.topMetrics("hash", null, null, null, null, null, null, null, 50, null, "no-such-env", null)).isEmpty();

    final PendingResponse pending = plansApi.requestPlanCapture(APP, ORM_HASH, ENV);
    assertThat(pending.pending()).isGreaterThanOrEqualTo(1);
    assertThat(pending.app()).isEqualTo(APP);
    assertThat(pending.env()).isEqualTo(ENV);
    assertThat(pending.label()).isEqualTo(ORM_LABEL);

    assertThatThrownBy(() -> plansApi.requestPlanCapture(APP, PLAIN_HASH, null))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(400));

    assertThatThrownBy(() -> plansApi.requestPlanCapture("no-such-app", ORM_HASH, null))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(404));

    // the capture request above is durably recorded and visible until collected
    final List<PendingPlan> pendingPlans = plansApi.listPendingPlans(null, null, null, null);
    assertThat(pendingPlans).extracting(PendingPlan::hash).contains(ORM_HASH);
    assertThat(pendingPlans).filteredOn(p -> ORM_HASH.equals(p.hash()))
      .allSatisfy(p -> {
        assertThat(p.requestedAt()).isNotNull();
        assertThat(p.label()).isEqualTo(ORM_LABEL);
      });
    assertThat(plansApi.listPendingPlans(APP, ENV, null, null)).extracting(PendingPlan::hash).contains(ORM_HASH);
    assertThat(plansApi.listPendingPlans("no-such-app", null, null, null)).isEmpty();
    // hash / label filters
    assertThat(plansApi.listPendingPlans(null, null, ORM_HASH, null)).extracting(PendingPlan::hash).contains(ORM_HASH);
    assertThat(plansApi.listPendingPlans(null, null, "no-such-hash", null)).isEmpty();
    assertThat(plansApi.listPendingPlans(null, null, null, ORM_LABEL)).extracting(PendingPlan::hash).contains(ORM_HASH);
    assertThat(plansApi.listPendingPlans(null, null, null, "no-such-label")).isEmpty();

    // an "any environment" capture (no env) is bucketed as "*" and is visible
    // regardless of env filter, since it may be collected in any environment
    final PendingResponse anyResp = plansApi.requestPlanCapture(APP, ORM_HASH, null);
    assertThat(anyResp.env()).isEqualTo("*");
    assertThat(plansApi.listPendingPlans(null, null, null, null))
      .anySatisfy(p -> {
        assertThat(p.hash()).isEqualTo(ORM_HASH);
        assertThat(p.env()).isEqualTo("*");
      });
    assertThat(plansApi.listPendingPlans(null, "no-such-env", null, null))
      .extracting(PendingPlan::hash).contains(ORM_HASH);

    seedQueryPlan();
    Thread.sleep(500);

    // ingesting the plan marks both the env-specific and any-env requests
    // collected (the any-env one having its env filled in), so they drop out
    assertThat(plansApi.listPendingPlans(null, null, null, null)).extracting(PendingPlan::hash).doesNotContain(ORM_HASH);

    final List<QueryPlanSummary> appPlans = plansApi.listPlans(APP, null, null, null, null, null, null, null, null);
    assertThat(appPlans).extracting(QueryPlanSummary::hash).contains(ORM_HASH);

    final List<QueryPlanSummary> byHashPlans = plansApi.listPlans(APP, null, null, ORM_HASH, null, null, null, null, null);
    assertThat(byHashPlans).extracting(QueryPlanSummary::hash).containsOnly(ORM_HASH);

    final List<QueryPlanSummary> byLabelPlans = plansApi.listPlans(APP, null, ORM_LABEL, null, null, null, null, null, null);
    assertThat(byLabelPlans).extracting(QueryPlanSummary::hash).contains(ORM_HASH);

    assertThat(plansApi.listPlans(APP, ENV, null, null, null, null, null, null, null))
      .extracting(QueryPlanSummary::hash).contains(ORM_HASH);
    assertThat(plansApi.listPlans(APP, "no-such-env", null, null, null, null, null, null, null))
      .isEmpty();

    final List<QueryPlanSummary> globalPlans = plansApi.listPlans(null, null, null, ORM_HASH, null, null, null, null, null);
    assertThat(globalPlans).extracting(QueryPlanSummary::hash).containsOnly(ORM_HASH);

    final long planId = appPlans.getFirst().id();
    final QueryPlan one = plansApi.getPlan(planId);
    assertThat(one.id()).isEqualTo(planId);
    assertThat(one.hash()).isEqualTo(ORM_HASH);
    assertThat(one.sql()).isNotBlank();

    assertThatThrownBy(() -> plansApi.getPlan(9999999L))
      .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.statusCode()).isEqualTo(404));

    final List<MissingPlanMetric> missingAfter = metricsApi.listMissingPlans(APP, null, null, null, null, null, null, null);
    assertThat(missingAfter).extracting(MissingPlanMetric::label).doesNotContain(ORM_LABEL);
  }

  private void rollup() {
    new Rollup(database, eventMinute).rollup();
  }

  private void rollupStale() {
    new Rollup(database, staleMinute).rollup();
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

  private void seedStaleMetric() {
    final String payload = """
      {
        "eventTime": %d,
        "appName": "%s",
        "environment": "%s",
        "dbs": [
          {
            "db": "db",
            "metrics": [
              {"name": "%s", "count": 7, "total": 5000, "mean": 700, "max": 900, "hash": "%s", "loc": "x.java:3", "sql": "select 3"}
            ]
          }
        ]
      }
      """.formatted(staleMinute.toEpochMilli(), APP, ENV, STALE_LABEL, STALE_HASH);
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

  @Test
  void topMetrics_perAppByDefault_aggregatedWithAllApps() throws InterruptedException {
    final String sharedName = "shared.txn.perapptest";
    seedSharedMetric("perapp-a", sharedName, "perapphasha000000000000000000001", 4, 400);
    seedSharedMetric("perapp-b", sharedName, "perapphashb000000000000000000001", 6, 600);
    Thread.sleep(200);
    rollup();

    final MetricsApi metricsApi = httpClient.create(MetricsApi.class);

    // Default (allApps=null/false): the shared name yields one row PER APP, each
    // with its app populated.
    final List<TopGroup> perApp = metricsApi.topMetrics("name", sharedName, null, null, null,
      "total", null, null, 50, null, null, null);
    assertThat(perApp).hasSize(2);
    assertThat(perApp).allSatisfy(g -> assertThat(g.app()).isNotNull());
    assertThat(perApp).extracting(TopGroup::app).containsExactlyInAnyOrder("perapp-a", "perapp-b");
    assertThat(perApp).extracting(TopGroup::count).containsExactlyInAnyOrder(4L, 6L);

    // allApps=true: the two apps collapse into a single cross-app row (app blank,
    // counts summed).
    final List<TopGroup> crossApp = metricsApi.topMetrics("name", sharedName, null, null, null,
      "total", null, null, 50, null, null, true);
    assertThat(crossApp).hasSize(1);
    assertThat(crossApp.get(0).app()).isNull();
    assertThat(crossApp.get(0).count()).isEqualTo(10L);
  }

  private void seedSharedMetric(String app, String name, String hash, int count, long total) {
    final String payload = """
      {
        "eventTime": %d,
        "appName": "%s",
        "environment": "%s",
        "dbs": [
          {
            "db": "db",
            "metrics": [
              {"name": "%s", "count": %d, "total": %d, "mean": 100, "max": 200, "hash": "%s", "loc": "x.java:9", "sql": "select 9"}
            ]
          }
        ]
      }
      """.formatted(eventMinute.toEpochMilli(), app, ENV, name, count, total, hash);
    final HttpResponse<String> res = httpClient.request()
      .path("api/ingest/metrics")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(payload)
      .POST()
      .asString();
    assertThat(res.statusCode()).isEqualTo(204);
  }
}
