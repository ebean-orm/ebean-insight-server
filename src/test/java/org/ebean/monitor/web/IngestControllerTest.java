package org.ebean.monitor.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import org.ebean.monitor.api.App;
import org.ebean.monitor.api.AppMetric;
import org.ebean.monitor.api.Env;
import org.ebean.monitor.api.ListResponse;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ebean.monitor.ResourceHelp.read;

@InjectTest
class IngestControllerTest {

  @Inject
  HttpClient httpClient;

  @Inject
  ApiControllerTestAPI apiControllerTestAPI;

  @Test
  void ingest() throws InterruptedException {

    final String bodyA = read("/request/req-3a.json");
    final String bodyB = read("/request/req-3b.json");
    final String bodyC = read("/request/req-3c.json");

      System.out.println("---------------- ingesting");
      ingest(bodyA);
      ingest(bodyB);
      ingest(bodyC);

      System.out.println("---------------- sleeping");
      // allow queue consumer to process
      Thread.sleep(500);

      final HttpResponse<ListResponse<Env>> envsResponse = getEnvironments();
      assertThat(envsResponse.statusCode()).isEqualTo(200);
      var envs = envsResponse.body();
      assertThat(envs.getList()).isNotEmpty();
      assertThat(envs.getList())
        .extracting(Env::getName)
        .contains("dev1");

      final HttpResponse<ListResponse<App>> appsResponse = getApps();
      assertThat(appsResponse.statusCode()).isEqualTo(200);
      assertThat(appsResponse.body().getList())
        .extracting(App::getName)
        .contains("int1");

      final App app1 = appsResponse.body().getList().getFirst();

      final HttpResponse<ListResponse<AppMetric>> appMetricsResponse = getAppMetrics(app1.getId());
      assertThat(appMetricsResponse.statusCode()).isEqualTo(200);
      var appMetrics = appMetricsResponse.body();
      assertThat(appMetrics.getList())
        .extracting(AppMetric::getName)
        .contains("OrderDao.findOrdersForPublishing");

      ingestQueryPlan();
      // allow queue consumer to process
      Thread.sleep(500);

    List<DQueryPlan> plans = new QDQueryPlan()
      .hash.eq("8a519a4c120289bd505a4a79c27f2895")
      .findList();

    assertThat(plans).isNotEmpty();
  }

  private void ingestQueryPlan() {
    final String planPayload = read("/request/queryPlan0.json");
    HttpResponse<String> hres = httpClient.request()
      .path("api/ingest/plans")
      .header("Content-Type", "application/json")
      .header("Insight-Key", "testHash")
      .body(planPayload)
      .POST()
      .asString();

    assertThat(hres.statusCode()).isEqualTo(204);
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

  private HttpResponse<ListResponse<Env>> getEnvironments() {
    return apiControllerTestAPI.getEnvs();
  }

  private HttpResponse<ListResponse<App>> getApps() {
    return apiControllerTestAPI.getApps();
  }

  private HttpResponse<ListResponse<AppMetric>> getAppMetrics(long appId) {
    return apiControllerTestAPI.getAppMetrics(appId);
  }
}
