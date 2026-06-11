package org.ebean.monitor.v1.web;

import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeChangeTest {

  private static final DApp APP = new DApp("app");
  private static final DEnv TEST = new DEnv("test");
  private static final DEnv DEV = new DEnv("dev");
  private static final Instant BASE = Instant.parse("2026-06-01T00:00:00Z");

  private int seq;

  private DQueryPlan plan(DEnv env, String hash, String shapeHash, int minute) {
    DQueryPlan p = new DQueryPlan(APP, env, null);
    p.setId(++seq);
    p.setHash(hash);
    p.setWhenCaptured(BASE.plusSeconds(minute * 60L));
    p.setPlanShapeHash(shapeHash);
    return p;
  }

  private long id(DQueryPlan p) {
    return p.getId();
  }

  @Test
  void changePoints_inSequence() {
    // A, A, B, B, A -> changes at first B and at final A
    DQueryPlan a1 = plan(TEST, "h1", "A", 1);
    DQueryPlan a2 = plan(TEST, "h1", "A", 2);
    DQueryPlan b1 = plan(TEST, "h1", "B", 3);
    DQueryPlan b2 = plan(TEST, "h1", "B", 4);
    DQueryPlan a3 = plan(TEST, "h1", "A", 5);

    // pass newest-first (as the query returns)
    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(a3, b2, b1, a2, a1));

    assertThat(changed).containsExactlyInAnyOrder(id(b1), id(a3));
    assertThat(changed).doesNotContain(id(a1), id(a2), id(b2));
  }

  @Test
  void earliestIsBaseline_notAChange() {
    DQueryPlan a1 = plan(TEST, "h1", "A", 1);
    DQueryPlan b1 = plan(TEST, "h1", "B", 2);

    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(b1, a1));

    assertThat(changed).containsExactly(id(b1));
    assertThat(changed).doesNotContain(id(a1));
  }

  @Test
  void envPartitionsAreIsolated() {
    // same hash, different env -> must not cross-trigger
    DQueryPlan tA = plan(TEST, "h1", "A", 1);
    DQueryPlan dB = plan(DEV, "h1", "B", 2);
    DQueryPlan tA2 = plan(TEST, "h1", "A", 3);

    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(tA2, dB, tA));

    assertThat(changed).isEmpty();
  }

  @Test
  void nullShapeNeverBaselineOrChange_andCarriesForward() {
    // A, null, A -> the trailing A must NOT be flagged (null skipped, baseline still A)
    DQueryPlan a1 = plan(TEST, "h1", "A", 1);
    DQueryPlan nul = plan(TEST, "h1", null, 2);
    DQueryPlan a2 = plan(TEST, "h1", "A", 3);

    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(a2, nul, a1));

    assertThat(changed).isEmpty();
  }

  @Test
  void nullShape_doesNotResetAcrossRealChange() {
    // A, null, B -> change recorded at B (null between is skipped)
    DQueryPlan a1 = plan(TEST, "h1", "A", 1);
    DQueryPlan nul = plan(TEST, "h1", null, 2);
    DQueryPlan b1 = plan(TEST, "h1", "B", 3);

    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(b1, nul, a1));

    assertThat(changed).containsExactly(id(b1));
  }

  @Test
  void allNullShapes_noChanges() {
    DQueryPlan n1 = plan(TEST, "h1", null, 1);
    DQueryPlan n2 = plan(TEST, "h1", null, 2);

    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(n2, n1));

    assertThat(changed).isEmpty();
  }

  @Test
  void differentHashesIsolated() {
    DQueryPlan h1a = plan(TEST, "h1", "A", 1);
    DQueryPlan h2b = plan(TEST, "h2", "B", 2);
    DQueryPlan h1a2 = plan(TEST, "h1", "A", 3);

    Set<Long> changed = V1QueryService.computeShapeChanges(List.of(h1a2, h2b, h1a));

    assertThat(changed).isEmpty();
  }

  @Test
  void toQueryPlanExposesShapeFields() {
    DQueryPlan p = new DQueryPlan(APP, TEST, null);
    p.setId(99);
    p.setHash("h1");
    p.setLabel("orm.Foo.bar");
    p.setWhenCaptured(BASE);
    p.setPlan("Sort\n  Seq Scan");
    p.setPlanShape("Sort\n key:t0.id");
    p.setPlanShapeHash("abc123");
    p.setPlanShapeAlgo(1);

    var dto = V1QueryService.toQueryPlan(p);
    assertThat(dto.planShape()).isEqualTo("Sort\n key:t0.id");
    assertThat(dto.planShapeHash()).isEqualTo("abc123");
    assertThat(dto.planShapeAlgo()).isEqualTo(1);
  }

  @Test
  void toQueryPlanSummary_shapeChangedFlag() {
    DQueryPlan p = new DQueryPlan(APP, TEST, null);
    p.setId(7);
    p.setHash("h1");
    p.setLabel("orm.Foo.bar");
    p.setWhenCaptured(BASE);
    p.setPlanShapeHash("abc123");

    var changed = V1QueryService.toQueryPlanSummary(p, true);
    assertThat(changed.shapeChanged()).isTrue();
    assertThat(changed.planShapeHash()).isEqualTo("abc123");

    var unchanged = V1QueryService.toQueryPlanSummary(p);
    assertThat(unchanged.shapeChanged()).isFalse();
  }
}
