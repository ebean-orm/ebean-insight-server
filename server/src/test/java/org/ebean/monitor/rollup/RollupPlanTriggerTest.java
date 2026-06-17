package org.ebean.monitor.rollup;

import io.avaje.config.Config;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RollupPlanTriggerTest {

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final List<String> captured = new ArrayList<>();
  private List<MissingPlanMetric> neverCapturedResult = new ArrayList<>();
  private List<MissingPlanMetric> stalePlanResult = new ArrayList<>();
  private List<RegressionPlanMetric> regressionResult = new ArrayList<>();

  @BeforeEach
  void enable() {
    Config.setProperty("autoplan.rollup.enabled", "true");
    Config.setProperty("autoplan.rollup.cooldownMinutes", "180");
    Config.setProperty("autoplan.rollup.neverCapturedLimit", "20");
    Config.setProperty("autoplan.rollup.stalePlanLimit", "10");
  }

  @AfterEach
  void reset() {
    Config.setProperty("autoplan.rollup.enabled", "false");
    captured.clear();
    neverCapturedResult = new ArrayList<>();
    stalePlanResult = new ArrayList<>();
    regressionResult = new ArrayList<>();
  }

  private RollupPlanTrigger trigger() {
    return new RollupPlanTrigger(
      _ -> neverCapturedResult,
      _ -> stalePlanResult,
      _ -> regressionResult,
      (app, key, _) -> captured.add(app + "|" + key),
      now::get);
  }

  /** Returns an event time at the given minute (hour=12, so minute=0 is also hour%60==0). */
  private static Instant atMinute(int minuteOfHour) {
    return ZonedDateTime.of(2025, 1, 1, 12, minuteOfHour, 0, 0, ZoneOffset.UTC).toInstant();
  }

  /** An event time at minute 0 of an hour — triggers both M10 AND M60 rules. */
  private static Instant atTopOfHour() {
    return ZonedDateTime.of(2025, 1, 1, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();
  }

  private static MissingPlanMetric metric(String app, String key) {
    return MissingPlanMetric.builder()
      .id(1L).app(app).label("orm." + key).key(key)
      .captureCount(0L).count(10L).totalMicros(5_000_000L)
      .meanMicros(500_000L).maxMicros(1_000_000L).windowMinutes(10L)
      .build();
  }

  // ── never-captured (M10) ────────────────────────────────────────────────────

  @Test
  void neverCaptured_firesOnM10Minute() {
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    trigger().onRollup(atMinute(10));
    assertThat(captured).containsExactly("myapp|hash1");
  }

  @Test
  void neverCaptured_skipsNonM10Minute() {
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    trigger().onRollup(atMinute(7));
    assertThat(captured).isEmpty();
  }

  @Test
  void neverCaptured_minute0_fires() {
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    trigger().onRollup(atMinute(0));
    assertThat(captured).contains("myapp|hash1");
  }

  @Test
  void neverCaptured_respectsCooldown_withinWindow() {
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    var t = trigger();
    t.onRollup(atMinute(10));
    now.addAndGet(10 * 60_000L); // +10 min — still in cooldown
    t.onRollup(atMinute(20));
    assertThat(captured).hasSize(1);
  }

  @Test
  void neverCaptured_cooldownExpires_afterWindow() {
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    var t = trigger();
    t.onRollup(atMinute(10));
    now.addAndGet(181L * 60_000L); // > 180 min
    t.onRollup(atMinute(20));
    assertThat(captured).hasSize(2);
  }

  @Test
  void neverCaptured_multipleMetrics_allCaptured() {
    neverCapturedResult = List.of(metric("app1", "h1"), metric("app1", "h2"), metric("app2", "h3"));
    trigger().onRollup(atMinute(10));
    assertThat(captured).containsExactlyInAnyOrder("app1|h1", "app1|h2", "app2|h3");
  }

  // ── stale plan (M60) ────────────────────────────────────────────────────────

  @Test
  void stalePlan_firesAtTopOfHour() {
    stalePlanResult = List.of(metric("myapp", "oldHash"));
    trigger().onRollup(atTopOfHour());
    assertThat(captured).contains("myapp|oldHash");
  }

  @Test
  void stalePlan_skipsNonTopOfHour() {
    stalePlanResult = List.of(metric("myapp", "oldHash"));
    trigger().onRollup(atMinute(10)); // M10, not M60
    assertThat(captured).isEmpty();
  }

  @Test
  void stalePlan_cooldownSharedWithNeverCaptured() {
    // same hash requested by never-captured first; stale-plan at top of hour should be skipped
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    stalePlanResult = List.of(metric("myapp", "hash1"));
    var t = trigger();
    t.onRollup(atMinute(10)); // never-captured fires, hash1 captured
    now.addAndGet(5 * 60_000L); // +5 min — still in cooldown
    t.onRollup(atTopOfHour()); // stale-plan fires too, but hash1 is in cooldown
    assertThat(captured).hasSize(1); // only the first request went through
  }

  @Test
  void stalePlan_separateHashNotBlockedByCooldown() {
    neverCapturedResult = List.of(metric("myapp", "hashA"));
    stalePlanResult = List.of(metric("myapp", "hashB")); // different hash
    var t = trigger();
    t.onRollup(atTopOfHour()); // both rules fire; different hashes → both captured
    assertThat(captured).containsExactlyInAnyOrder("myapp|hashA", "myapp|hashB");
  }

  private static RegressionPlanMetric regression(String app, String key) {
    return new RegressionPlanMetric(app, key, "orm." + key, 100_000L, 20_000L); // 5x regression
  }

  // ── regression (M60) ────────────────────────────────────────────────────────

  @Test
  void regression_firesAtTopOfHour() {
    regressionResult = List.of(regression("myapp", "slowHash"));
    trigger().onRollup(atTopOfHour());
    assertThat(captured).contains("myapp|slowHash");
  }

  @Test
  void regression_skipsNonTopOfHour() {
    regressionResult = List.of(regression("myapp", "slowHash"));
    trigger().onRollup(atMinute(10)); // M10 only, not M60
    assertThat(captured).isEmpty();
  }

  @Test
  void regression_cooldownSharedWithNeverCaptured() {
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    regressionResult = List.of(regression("myapp", "hash1")); // same hash
    var t = trigger();
    t.onRollup(atMinute(10)); // never-captured fires
    now.addAndGet(5 * 60_000L);
    t.onRollup(atTopOfHour()); // regression fires too, but hash1 in cooldown
    assertThat(captured).hasSize(1);
  }

  @Test
  void regression_differentHashNotBlocked() {
    regressionResult = List.of(regression("myapp", "hashR1"), regression("myapp", "hashR2"));
    trigger().onRollup(atTopOfHour());
    assertThat(captured).containsExactlyInAnyOrder("myapp|hashR1", "myapp|hashR2");
  }

  @Test
  void allThreeRules_fireAtTopOfHour_distinctHashes() {
    neverCapturedResult = List.of(metric("app", "neverHash"));
    stalePlanResult = List.of(metric("app", "staleHash"));
    regressionResult = List.of(regression("app", "regressHash"));
    trigger().onRollup(atTopOfHour());
    assertThat(captured).containsExactlyInAnyOrder("app|neverHash", "app|staleHash", "app|regressHash");
  }

  // ── general ─────────────────────────────────────────────────────────────────

  @Test
  void disabled_isNoop() {
    Config.setProperty("autoplan.rollup.enabled", "false");
    neverCapturedResult = List.of(metric("myapp", "hash1"));
    stalePlanResult = List.of(metric("myapp", "oldHash"));
    regressionResult = List.of(regression("myapp", "slowHash"));
    trigger().onRollup(atTopOfHour());
    assertThat(captured).isEmpty();
  }

  @Test
  void emptyResults_noCapturesRequested() {
    trigger().onRollup(atTopOfHour());
    assertThat(captured).isEmpty();
  }
}

