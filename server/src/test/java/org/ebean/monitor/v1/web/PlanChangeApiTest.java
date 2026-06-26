package org.ebean.monitor.v1.web;

import io.avaje.inject.test.InjectTest;
import io.avaje.jex.http.BadRequestException;
import io.avaje.jex.http.NotFoundException;
import io.ebean.Database;
import jakarta.inject.Inject;
import org.ebean.monitor.config.GlobalMetrics;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.DQueryPlanChange;
import org.ebean.monitor.domain.DQueryPlanChange.ChangeType;
import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@InjectTest
class PlanChangeApiTest {

  @Inject static Database database;

  private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

  private final V1QueryService service = new V1QueryService(null);

  @BeforeAll
  static void setup() {
    GlobalMetrics.init();
  }

  private DApp app(String name) {
    DApp a = new DApp(name);
    a.save();
    return a;
  }

  private DEnv env(String name) {
    DEnv existing = database.find(DEnv.class).where().eq("name", name).findOne();
    if (existing != null) {
      return existing;
    }
    DEnv e = new DEnv(name);
    e.save();
    return e;
  }

  private DQueryPlan plan(DApp app, DEnv env, String hash, String shapeHash, long timeMicros, Instant when) {
    DQueryPlan p = new DQueryPlan(app, env, null);
    p.setHash(hash);
    p.setLabel("orm.Foo.find");
    p.setQueryTimeMicros(timeMicros);
    p.setWhenCaptured(when);
    p.setPlan("EXPLAIN " + shapeHash);
    p.setPlanShape("shape:" + shapeHash);
    p.setPlanShapeHash(shapeHash);
    p.setPlanShapeAlgo(1);
    p.save();
    return p;
  }

  private DQueryPlanChange change(DApp app, DEnv env, String hash, ChangeType type,
                                  DQueryPlan from, DQueryPlan to, Instant detectedAt) {
    DQueryPlanChange c = new DQueryPlanChange(app, env, hash, to);
    c.setLabel("orm.Foo.find");
    c.setKind("orm");
    c.setType("Foo");
    c.setChangeType(type);
    c.setFromPlan(from);
    c.setFromShapeHash(from == null ? null : from.planShapeHash());
    c.setToShapeHash(to.planShapeHash());
    c.setAlgo(1);
    c.setFromQueryTimeMicros(from == null ? null : from.queryTimeMicros());
    c.setToQueryTimeMicros(to.queryTimeMicros());
    c.setWhenCaptured(to.whenCaptured());
    c.setDetectedAt(detectedAt);
    c.save();
    return c;
  }

  @Test
  void recentChanges_orderedByDetectedAtDesc_withFilters() {
    String suffix = String.valueOf(System.nanoTime());
    DApp app = app("pca-" + suffix);
    DEnv prod = env("prod");
    DEnv dev = env("dev");
    String h1 = "pca-h1-" + suffix;
    String h2 = "pca-h2-" + suffix;

    DQueryPlan p1a = plan(app, prod, h1, "AAA", 100, BASE);
    DQueryPlan p1b = plan(app, prod, h1, "BBB", 250, BASE.plusSeconds(60));
    DQueryPlan p2a = plan(app, dev, h2, "CCC", 70, BASE.plusSeconds(30));

    // detection times deliberately out of insert order to prove ordering by detectedAt desc
    change(app, prod, h1, ChangeType.FIRST, null, p1a, BASE.plusSeconds(5));
    DQueryPlanChange changed = change(app, prod, h1, ChangeType.CHANGED, p1a, p1b, BASE.plusSeconds(65));
    change(app, dev, h2, ChangeType.FIRST, null, p2a, BASE.plusSeconds(35));

    // all events for this app, newest detection first
    List<PlanChange> all = service.listPlanChanges(app.getName(), null, null, null, null, null, null, null, null, 50);
    assertThat(all).hasSize(3);
    assertThat(all.get(0).detectedAt()).isEqualTo(BASE.plusSeconds(65)); // CHANGED on h1
    assertThat(all.get(1).detectedAt()).isEqualTo(BASE.plusSeconds(35)); // FIRST on h2
    assertThat(all.get(2).detectedAt()).isEqualTo(BASE.plusSeconds(5));  // FIRST on h1

    // env filter
    List<PlanChange> prodOnly = service.listPlanChanges(app.getName(), "prod", null, null, null, null, null, null, null, 50);
    assertThat(prodOnly).hasSize(2);
    assertThat(prodOnly).allMatch(c -> c.envName().equals("prod"));

    // hash filter
    List<PlanChange> hash1 = service.listPlanChanges(app.getName(), null, h1, null, null, null, null, null, null, 50);
    assertThat(hash1).hasSize(2);
    assertThat(hash1).allMatch(c -> c.hash().equals(h1));

    // changeType filter (case-insensitive)
    List<PlanChange> changedOnly = service.listPlanChanges(app.getName(), null, null, "changed", null, null, null, null, null, 50);
    assertThat(changedOnly).hasSize(1);

    // label / kind / type tag filters
    assertThat(service.listPlanChanges(app.getName(), null, null, null, "orm.Foo.find", null, null, null, null, 50)).hasSize(3);
    assertThat(service.listPlanChanges(app.getName(), null, null, null, null, "orm", null, null, null, 50)).hasSize(3);
    assertThat(service.listPlanChanges(app.getName(), null, null, null, null, null, "Foo", null, null, 50)).hasSize(3);
    assertThat(service.listPlanChanges(app.getName(), null, null, null, "no-match", null, null, null, null, 50)).isEmpty();
    assertThat(service.listPlanChanges(app.getName(), null, null, null, null, "no-match", null, null, null, 50)).isEmpty();
    assertThat(service.listPlanChanges(app.getName(), null, null, null, null, null, "no-match", null, null, 50)).isEmpty();
    PlanChange c = changedOnly.get(0);
    assertThat(c.changeType()).isEqualTo("CHANGED");
    assertThat(c.id()).isEqualTo((long) changed.getId());
    assertThat(c.fromPlanId()).isEqualTo((long) p1a.getId());
    assertThat(c.toPlanId()).isEqualTo((long) p1b.getId());
    assertThat(c.fromShapeHash()).isEqualTo("AAA");
    assertThat(c.toShapeHash()).isEqualTo("BBB");
    assertThat(c.fromQueryTimeMicros()).isEqualTo(100L);
    assertThat(c.toQueryTimeMicros()).isEqualTo(250L);
    assertThat(c.algo()).isEqualTo(1);

    // limit
    assertThat(service.listPlanChanges(app.getName(), null, null, null, null, null, null, null, null, 1)).hasSize(1);
  }

  @Test
  void firstEvent_hasNullFromFields() {
    String suffix = String.valueOf(System.nanoTime());
    DApp app = app("pca-first-" + suffix);
    DEnv prod = env("prod");
    String h = "pca-fh-" + suffix;
    DQueryPlan p = plan(app, prod, h, "AAA", 80, BASE);
    change(app, prod, h, ChangeType.FIRST, null, p, BASE.plusSeconds(5));

    List<PlanChange> first = service.listPlanChanges(app.getName(), null, null, "FIRST", null, null, null, null, null, 50);
    assertThat(first).hasSize(1);
    PlanChange c = first.get(0);
    assertThat(c.changeType()).isEqualTo("FIRST");
    assertThat(c.fromPlanId()).isNull();
    assertThat(c.fromShapeHash()).isNull();
    assertThat(c.fromQueryTimeMicros()).isNull();
    assertThat(c.toShapeHash()).isEqualTo("AAA");
  }

  @Test
  void unknownApp_returnsEmpty() {
    assertThat(service.listPlanChanges("no-such-app-" + System.nanoTime(), null, null, null, null, null, null, null, null, 50))
      .isEmpty();
  }

  @Test
  void invalidChangeType_throwsBadRequest() {
    assertThatThrownBy(() -> service.listPlanChanges(null, null, null, "BOGUS", null, null, null, null, null, 50))
      .isInstanceOf(BadRequestException.class);
  }

  @Test
  void getPlanChange_returnsFullFromAndToPlans() {
    String suffix = String.valueOf(System.nanoTime());
    DApp app = app("pca-detail-" + suffix);
    DEnv prod = env("prod");
    String h = "pca-dh-" + suffix;
    DQueryPlan p1 = plan(app, prod, h, "AAA", 100, BASE);
    DQueryPlan p2 = plan(app, prod, h, "BBB", 250, BASE.plusSeconds(60));
    DQueryPlanChange changed = change(app, prod, h, ChangeType.CHANGED, p1, p2, BASE.plusSeconds(65));

    PlanChangeDetail detail = service.getPlanChange(changed.getId());
    assertThat(detail.change().id()).isEqualTo((long) changed.getId());
    assertThat(detail.change().changeType()).isEqualTo("CHANGED");
    assertThat(detail.fromPlan()).isNotNull();
    assertThat(detail.fromPlan().id()).isEqualTo((long) p1.getId());
    assertThat(detail.fromPlan().plan()).isEqualTo("EXPLAIN AAA");
    assertThat(detail.fromPlan().planShape()).isEqualTo("shape:AAA");
    assertThat(detail.fromPlan().planShapeHash()).isEqualTo("AAA");
    assertThat(detail.toPlan().id()).isEqualTo((long) p2.getId());
    assertThat(detail.toPlan().plan()).isEqualTo("EXPLAIN BBB");
    assertThat(detail.toPlan().planShape()).isEqualTo("shape:BBB");
    assertThat(detail.toPlan().planShapeHash()).isEqualTo("BBB");
  }

  @Test
  void getPlanChange_first_hasNullFromPlan() {
    String suffix = String.valueOf(System.nanoTime());
    DApp app = app("pca-detfirst-" + suffix);
    DEnv prod = env("prod");
    String h = "pca-dfh-" + suffix;
    DQueryPlan p = plan(app, prod, h, "AAA", 80, BASE);
    DQueryPlanChange first = change(app, prod, h, ChangeType.FIRST, null, p, BASE.plusSeconds(5));

    PlanChangeDetail detail = service.getPlanChange(first.getId());
    assertThat(detail.change().changeType()).isEqualTo("FIRST");
    assertThat(detail.fromPlan()).isNull();
    assertThat(detail.toPlan().id()).isEqualTo((long) p.getId());
  }

  @Test
  void getPlanChange_unknownId_throwsNotFound() {
    assertThatThrownBy(() -> service.getPlanChange(99999999L))
      .isInstanceOf(NotFoundException.class);
  }
}
