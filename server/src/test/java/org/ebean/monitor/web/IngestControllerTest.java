package org.ebean.monitor.web;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import org.ebean.monitor.api.App;
import org.ebean.monitor.api.AppMetric;
import org.ebean.monitor.api.Env;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DEnv;
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

      var envNames = DEnv.find.findAll().stream().map(Env::getName).toList();
      assertThat(envNames).contains("dev1");

      var apps = DApp.find.findAll();
      assertThat(apps).extracting(App::getName).contains("int1");
      final App app1 = apps.getFirst();

      var appMetrics = DAppMetric.find.byApp(DApp.find.ref(app1.getId()));
      assertThat(appMetrics)
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
}
