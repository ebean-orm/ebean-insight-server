package org.ebean.monitor.ingest;

import io.avaje.config.Config;
import io.avaje.inject.test.InjectTest;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.config.GlobalMetrics;
import org.ebean.monitor.domain.DQueryPlanChange;
import org.ebean.monitor.domain.DQueryPlanChange.ChangeType;
import org.ebean.monitor.domain.query.QDQueryPlanChange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@InjectTest
class PlanChangeDetectionTest {

  @Inject static Database database;

  // two structurally distinct postgres EXPLAIN plans + a third identical to the first
  private static final String PLAN_A =
      "Seq Scan on public.foo  (cost=0.00..1.00 rows=1 width=10)\n  Filter: (id = 5)";
  private static final String PLAN_B =
      "Index Scan using foo_pk on public.foo  (cost=0.42..8.44 rows=1 width=10)\n  Index Cond: (id = 5)";

  @BeforeAll
  static void setup() {
    GlobalMetrics.init();
  }

  private IngestMessage ingest() {
    return new IngestMessage(database, new ProcessHeader(), new ProcessMetrics(), io.avaje.jsonb.Jsonb.instance());
  }

  private static QueryPlanRequest req(String env, String hash, String plan, Instant when) {
    QueryPlanRequest r = new QueryPlanRequest();
    r.appName = "pcd-app";
    r.environment = env;
    QueryPlanRequest.QPlan p = new QueryPlanRequest.QPlan();
    p.hash = hash;
    p.label = "orm.Foo.find";
    p.sql = "select * from foo where id = ?";
    p.bind = "[5]";
    p.plan = plan;
    p.queryTimeMicros = 100;
    p.captureCount = 1;
    p.captureMicros = 50;
    p.whenCaptured = when.toString();
    r.plans.add(p);
    return r;
  }

  private List<DQueryPlanChange> events(String hash) {
    return new QDQueryPlanChange().hash.eq(hash).orderBy().whenCaptured.asc().findList();
  }

  @Test
  void firstCapture_emitsFirst_thenChangeOnDifferingShape_noneOnSame() {
    String hash = "pcd-seq-" + System.nanoTime();
    Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
    var ingest = ingest();

    ingest.ingestQueryPlans(req("prod", hash, PLAN_A, t0));
    ingest.ingestQueryPlans(req("prod", hash, PLAN_B, t0.plusSeconds(60)));
    ingest.ingestQueryPlans(req("prod", hash, PLAN_B, t0.plusSeconds(120)));

    List<DQueryPlanChange> ev = events(hash);
    assertThat(ev).hasSize(2);
    assertThat(ev.get(0).changeType()).isEqualTo(ChangeType.FIRST);
    assertThat(ev.get(0).fromPlan()).isNull();
    assertThat(ev.get(0).fromShapeHash()).isNull();
    assertThat(ev.get(0).toShapeHash()).isNotBlank();
    assertThat(ev.get(0).algo()).isEqualTo(1);

    assertThat(ev.get(1).changeType()).isEqualTo(ChangeType.CHANGED);
    assertThat(ev.get(1).fromPlan()).isNotNull();
    assertThat(ev.get(1).fromShapeHash()).isEqualTo(ev.get(0).toShapeHash());
    assertThat(ev.get(1).toShapeHash()).isNotEqualTo(ev.get(0).toShapeHash());
    assertThat(ev.get(1).fromQueryTimeMicros()).isEqualTo(100L);
  }

  @Test
  void envPartitionsIsolated() {
    String hash = "pcd-env-" + System.nanoTime();
    Instant t0 = Instant.parse("2026-06-02T00:00:00Z");
    var ingest = ingest();

    // prod sees A then B (a change); dev sees only A (no change after its first)
    ingest.ingestQueryPlans(req("prod", hash, PLAN_A, t0));
    ingest.ingestQueryPlans(req("dev", hash, PLAN_A, t0.plusSeconds(10)));
    ingest.ingestQueryPlans(req("prod", hash, PLAN_B, t0.plusSeconds(60)));

    List<DQueryPlanChange> ev = events(hash);
    // prod: FIRST + CHANGED ; dev: FIRST  => 3 total
    assertThat(ev).hasSize(3);
    long prodChanges = ev.stream()
        .filter(e -> e.env().getName().equals("prod") && e.changeType() == ChangeType.CHANGED).count();
    long devChanges = ev.stream()
        .filter(e -> e.env().getName().equals("dev") && e.changeType() == ChangeType.CHANGED).count();
    assertThat(prodChanges).isEqualTo(1);
    assertThat(devChanges).isEqualTo(0);
  }

  @Test
  void idempotent_reingestSamePlanRows_noDuplicateEvents() {
    String hash = "pcd-idem-" + System.nanoTime();
    Instant t0 = Instant.parse("2026-06-03T00:00:00Z");
    var ingest = ingest();

    ingest.ingestQueryPlans(req("prod", hash, PLAN_A, t0));
    int afterFirst = events(hash).size();
    // re-detect over the existing rows: a fresh capture identical in shape adds no event,
    // and the previously-created FIRST event is not duplicated.
    ingest.ingestQueryPlans(req("prod", hash, PLAN_A, t0.plusSeconds(60)));

    List<DQueryPlanChange> ev = events(hash);
    assertThat(afterFirst).isEqualTo(1);
    assertThat(ev).hasSize(1); // still just the single FIRST event
    assertThat(ev.get(0).changeType()).isEqualTo(ChangeType.FIRST);
  }

  @Test
  void firstObservedDisabled_suppressesFirst_butKeepsChanged() {
    String prev = Config.getNullable("plans.change.firstObserved");
    Config.setProperty("plans.change.firstObserved", "false");
    try {
      String hash = "pcd-nofirst-" + System.nanoTime();
      Instant t0 = Instant.parse("2026-06-04T00:00:00Z");
      var ingest = ingest(); // constructed after the config change

      ingest.ingestQueryPlans(req("prod", hash, PLAN_A, t0));
      ingest.ingestQueryPlans(req("prod", hash, PLAN_B, t0.plusSeconds(60)));

      List<DQueryPlanChange> ev = events(hash);
      assertThat(ev).hasSize(1);
      assertThat(ev.get(0).changeType()).isEqualTo(ChangeType.CHANGED);
    } finally {
      if (prev == null) {
        Config.clearProperty("plans.change.firstObserved");
      } else {
        Config.setProperty("plans.change.firstObserved", prev);
      }
    }
  }
}
