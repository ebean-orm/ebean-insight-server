package org.ebean.monitor.forward;

import io.avaje.config.Config;
import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.web.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AutoPlanTriggerTest {

  private RecordingMessageService messages;
  private final AtomicLong now = new AtomicLong(1_000_000L);

  @BeforeEach
  void enable() {
    Config.setProperty("autoplan.enabled", "true");
    Config.setProperty("autoplan.cooldownMinutes", "180");
    Config.setProperty("autoplan.maxTracked", "5000");
    messages = new RecordingMessageService();
  }

  @AfterEach
  void reset() {
    Config.setProperty("autoplan.enabled", "false");
  }

  private AutoPlanTrigger trigger(PlanThresholds thresholds) {
    return new AutoPlanTrigger(messages, thresholds, now::get);
  }

  private static MetricRequest req(String appName, String env, MetricData... metrics) {
    var r = new MetricRequest();
    r.appName = appName;
    r.environment = env;
    var db = new MetricDbData();
    db.db = "h2";
    db.metrics = new ArrayList<>(List.of(metrics));
    r.dbs = new ArrayList<>(List.of(db));
    return r;
  }

  private static MetricData metric(String hash, long mean, long count) {
    var m = new MetricData();
    m.name = "orm.User.findById";
    m.hash = hash;
    m.mean = mean;
    m.count = count;
    return m;
  }

  @Test
  void triggers_on_slow_hashed_query() {
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    assertThat(messages.calls).containsExactly("app1|prod|qp:hashA");
  }

  @Test
  void skips_below_threshold() {
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", metric("hashA", 50_000, 5)));
    assertThat(messages.calls).isEmpty();
  }

  @Test
  void skips_missing_hash() {
    var m = metric(null, 200_000, 5);
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", m));
    assertThat(messages.calls).isEmpty();
  }

  @Test
  void skips_zero_count() {
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 0)));
    assertThat(messages.calls).isEmpty();
  }

  @Test
  void respects_cooldown_within_window() {
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    now.addAndGet(60_000L); // +1 min
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    assertThat(messages.calls).hasSize(1);
  }

  @Test
  void cooldown_expires_after_window() {
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    now.addAndGet(181L * 60_000L); // > 180 min
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    assertThat(messages.calls).hasSize(2);
  }

  @Test
  void disabled_by_config_is_noop() {
    Config.setProperty("autoplan.enabled", "false");
    var t = trigger(new GlobalPlanThresholds(100_000));
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    assertThat(messages.calls).isEmpty();
  }

  @Test
  void per_query_override_higher_suppresses_global_trigger() {
    var custom = new MapThresholds(100_000)
      .with("hashA", 500_000); // require 500ms for this hash
    var t = trigger(custom);
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5))); // 200ms < 500ms
    assertThat(messages.calls).isEmpty();
  }

  @Test
  void per_query_override_lower_triggers_below_global() {
    var custom = new MapThresholds(100_000)
      .with("hashA", 10_000); // sensitive: 10ms
    var t = trigger(custom);
    t.onIngest(req("app1", "prod", metric("hashA", 50_000, 5))); // 50ms > 10ms
    assertThat(messages.calls).containsExactly("app1|prod|qp:hashA");
  }

  @Test
  void per_query_threshold_missing_falls_back_to_global() {
    var custom = new MapThresholds(100_000); // no entry for hashA
    var t = trigger(custom);
    t.onIngest(req("app1", "prod", metric("hashA", 200_000, 5)));
    assertThat(messages.calls).containsExactly("app1|prod|qp:hashA");
  }

  /** Records (appName|env|message) per pushMessage call. */
  private static final class RecordingMessageService extends MessageService {
    final List<String> calls = new ArrayList<>();

    @Override
    public int pushMessage(String appName, String environment, String message) {
      calls.add(appName + "|" + environment + "|" + message);
      return 1;
    }
  }

  private static final class MapThresholds implements PlanThresholds {
    private final long defaultMicros;
    private final Map<String, Long> overrides = new HashMap<>();

    MapThresholds(long defaultMicros) {
      this.defaultMicros = defaultMicros;
    }

    MapThresholds with(String hash, long micros) {
      overrides.put(hash, micros);
      return this;
    }

    @Override
    public long thresholdMicros(String appName, String hash) {
      Long v = overrides.get(hash);
      return v != null ? v : defaultMicros;
    }
  }
}
