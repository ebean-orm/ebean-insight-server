package org.ebean.monitor.ingest;

import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.config.GlobalMetrics;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies a captured plan's v2 identity (name/kind/type/label) is resolved at
 * ingest from the matched metric, with a flat-label fallback when no metric exists.
 */
@InjectTest
class PlanIdentityTest {

  @Inject static Database database;

  @BeforeAll
  static void setup() {
    GlobalMetrics.init();
  }

  private IngestMessage ingest() {
    return new IngestMessage(database, new ProcessHeader(), new ProcessMetrics(), io.avaje.jsonb.Jsonb.instance());
  }

  private DApp app(String name) {
    DApp existing = new QDApp().name.eq(name).findOne();
    if (existing != null) {
      return existing;
    }
    DApp app = new DApp(name);
    database.save(app);
    return app;
  }

  private static QueryPlanRequest req(String appName, String hash, String flatLabel) {
    QueryPlanRequest r = new QueryPlanRequest();
    r.appName = appName;
    r.environment = "prod";
    QueryPlanRequest.QPlan p = new QueryPlanRequest.QPlan();
    p.hash = hash;
    p.label = flatLabel;
    p.sql = "select * from foo where id = ?";
    p.bind = "[5]";
    p.plan = "Seq Scan on public.foo  (cost=0.00..1.00 rows=1 width=10)";
    p.queryTimeMicros = 100;
    p.captureCount = 1;
    p.captureMicros = 50;
    p.whenCaptured = Instant.parse("2026-06-01T00:00:00Z").toString();
    r.plans.add(p);
    return r;
  }

  private DQueryPlan planFor(String hash) {
    return new QDQueryPlan().hash.eq(hash).findOne();
  }

  @Test
  void identityFromMatchedMetric() {
    String appName = "pit-metric-" + System.nanoTime();
    String hash = "pit-h-" + System.nanoTime();
    DApp app = app(appName);
    String tags = "{\"kind\":\"orm\",\"type\":\"Customer\",\"label\":\"Customer.findList\"}";
    database.save(new DAppMetric(app, hash, "ebean.query", tags, true));

    ingest().ingestQueryPlans(req(appName, hash, "orm.ignored.flat"));

    DQueryPlan plan = planFor(hash);
    assertThat(plan).isNotNull();
    assertThat(plan.name()).isEqualTo("ebean.query");
    assertThat(plan.kind()).isEqualTo("orm");
    assertThat(plan.type()).isEqualTo("Customer");
    assertThat(plan.label()).isEqualTo("Customer.findList");
  }

  @Test
  void fallbackFromFlatLabelWhenNoMetric() {
    String appName = "pit-nometric-" + System.nanoTime();
    String hash = "pit-h2-" + System.nanoTime();
    app(appName);

    ingest().ingestQueryPlans(req(appName, hash, "orm.Foo.find"));

    DQueryPlan plan = planFor(hash);
    assertThat(plan).isNotNull();
    assertThat(plan.name()).isEqualTo("ebean.query");
    assertThat(plan.kind()).isEqualTo("orm");
    assertThat(plan.type()).isNull();
    assertThat(plan.label()).isEqualTo("Foo.find");
  }

  @Test
  void explicitClientIdentityWhenNoMetric() {
    String appName = "pit-explicit-" + System.nanoTime();
    String hash = "pit-h3-" + System.nanoTime();
    app(appName);

    QueryPlanRequest r = req(appName, hash, "Customer.findList");
    r.plans.get(0).kind = "orm";
    r.plans.get(0).type = "Customer";
    ingest().ingestQueryPlans(r);

    DQueryPlan plan = planFor(hash);
    assertThat(plan).isNotNull();
    assertThat(plan.name()).isEqualTo("ebean.query");
    assertThat(plan.kind()).isEqualTo("orm");
    assertThat(plan.type()).isEqualTo("Customer");
    assertThat(plan.label()).isEqualTo("Customer.findList");
  }
}
