package org.ebean.monitor.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import org.ebean.monitor.api.AppMetric;
import org.ebean.monitor.api.ListResponse;
import org.ebean.monitor.api.PendingResponse;
import org.ebean.monitor.api.QueryPlan;
import org.ebean.monitor.api.QueryPlanSummary;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ebean.monitor.ResourceHelp.read;

@InjectTest
class ApiControllerQueryPlanTest {

  @Inject
  HttpClient httpClient;

  @Inject
  ApiControllerTestAPI apiControllerTestAPI;

  @Test
  void queryPlanEndpoints() throws InterruptedException {
    ingest(read("/request/req-app1-metric.json"));
    Thread.sleep(500);

    long appId = apiControllerTestAPI.getApps().body().getList().stream()
      .filter(a -> "app1".equals(a.getName()))
      .findFirst()
      .orElseThrow()
      .getId();
    AppMetric target = apiControllerTestAPI.getAppMetrics(appId).body().getList().stream()
      .filter(m -> "OrderDao.findOrdersForPublishing".equals(m.getName()))
      .findFirst()
      .orElseThrow();
    int appMetricId = (int) target.getId();

    HttpResponse<PendingResponse> reqRes =
      apiControllerTestAPI.requestQueryPlan(appMetricId, "local");
    assertThat(reqRes.statusCode()).isEqualTo(201);
    assertThat(reqRes.body().pending).isEqualTo(1);

    ingestQueryPlan(read("/request/queryPlan0.json"));
    Thread.sleep(500);

    HttpResponse<ListResponse<QueryPlan>> plansRes = apiControllerTestAPI.getQueryPlans(appMetricId, null);
    assertThat(plansRes.statusCode()).isEqualTo(200);
    List<QueryPlan> plans = plansRes.body().getList();
    assertThat(plans).isNotEmpty();
    QueryPlan p = plans.getFirst();
    assertThat(p.hash).isEqualTo("8a519a4c120289bd505a4a79c27f2895");
    assertThat(p.appMetricId).isEqualTo(appMetricId);
    assertThat(p.sql).isNotBlank();
    assertThat(p.plan).isNotBlank();
    assertThat(p.whenCaptured).isNotNull();

    HttpResponse<QueryPlan> oneRes = apiControllerTestAPI.getQueryPlan((int) p.id);
    assertThat(oneRes.statusCode()).isEqualTo(200);
    QueryPlan one = oneRes.body();
    assertThat(one.id).isEqualTo(p.id);
    assertThat(one.hash).isEqualTo("8a519a4c120289bd505a4a79c27f2895");
    assertThat(one.sql).isNotBlank();
    assertThat(one.plan).isNotBlank();

    HttpResponse<String> missingRes = httpClient.request()
      .path("api/queryplan/plan/999999")
      .GET()
      .asString();
    assertThat(missingRes.statusCode()).isEqualTo(404);

    HttpResponse<ListResponse<QueryPlanSummary>> recentRes = apiControllerTestAPI.getRecentQueryPlans(null, null, null, null, null, null);
    assertThat(recentRes.statusCode()).isEqualTo(200);
    assertThat(recentRes.body().getList())
      .isNotEmpty()
      .extracting(s -> s.hash)
      .contains("8a519a4c120289bd505a4a79c27f2895");

    HttpResponse<ListResponse<QueryPlanSummary>> capped = apiControllerTestAPI.getRecentQueryPlans(999, null, null, null, null, null);
    assertThat(capped.statusCode()).isEqualTo(200);
    assertThat(capped.body().getList()).hasSizeLessThanOrEqualTo(100);

    String hash = "8a519a4c120289bd505a4a79c27f2895";
    String label = "MyLabel.findOrdersForPublishing";

    // hash filter (match / no-match)
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, null, null, null, hash, null).body().getList())
      .extracting(s -> s.hash).containsOnly(hash);
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, null, null, null, "nomatch", null).body().getList())
      .isEmpty();

    // app filter (match / no-match)
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, "app1", null, null, null, null).body().getList())
      .extracting(s -> s.hash).contains(hash);
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, "no-such-app", null, null, null, null).body().getList())
      .isEmpty();

    // environment filter (match / no-match)
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, null, "local", null, null, null).body().getList())
      .extracting(s -> s.hash).contains(hash);
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, null, "no-such-env", null, null, null).body().getList())
      .isEmpty();

    // label filter
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, null, null, label, null, null).body().getList())
      .extracting(s -> s.label).containsOnly(label);

    // sinceMinutes recency window (just-captured plan is within 60 min; 0/negative ignored)
    assertThat(apiControllerTestAPI.getRecentQueryPlans(null, null, null, null, null, 60).body().getList())
      .extracting(s -> s.hash).contains(hash);

    // per-metric count cap
    assertThat(apiControllerTestAPI.getQueryPlans(appMetricId, 1).body().getList())
      .hasSizeLessThanOrEqualTo(1);
  }

  private void ingest(String metricsPayload) {
    HttpResponse<String> hres = httpClient.request()
      .path("api/ingest/metrics")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(metricsPayload)
      .POST()
      .asString();

    assertThat(hres.statusCode()).isEqualTo(204);
  }

  private void ingestQueryPlan(String planPayload) {
    HttpResponse<String> hres = httpClient.request()
      .path("api/ingest/plans")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(planPayload)
      .POST()
      .asString();

    assertThat(hres.statusCode()).isEqualTo(204);
  }
}
