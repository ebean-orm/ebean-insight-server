package org.ebean.monitor.ingest;

import io.avaje.config.Config;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.api.QueryPlanRequest;
import org.ebean.monitor.forward.AutoPlanTrigger;
import org.ebean.monitor.forward.ForwardConfig;
import org.ebean.monitor.forward.GlobalPlanThresholds;
import org.ebean.monitor.forward.MetricForwarder;
import org.ebean.monitor.forward.OtlpMetricMapper;
import org.ebean.monitor.forward.QueryPlanLogger;
import org.ebean.monitor.web.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IngestQueueConsumerStoreFlagTest {

  private static class CountingIngestMessage extends IngestMessage {
    final AtomicInteger metricCalls = new AtomicInteger();
    final AtomicInteger planCalls = new AtomicInteger();

    CountingIngestMessage() {
      super(null, null, null);
    }

    @Override
    public void ingest(MetricRequest request) {
      metricCalls.incrementAndGet();
    }

    @Override
    public void ingestQueryPlans(QueryPlanRequest request) {
      planCalls.incrementAndGet();
    }
  }

  @AfterEach
  void resetConfig() {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("plans.store.enabled", "true");
    Config.setProperty("forward.otel.enabled", "false");
    Config.setProperty("autoplan.enabled", "false");
  }

  private static MetricForwarder disabledForwarder() {
    Config.setProperty("forward.otel.enabled", "false");
    return new MetricForwarder(new ForwardConfig(), new OtlpMetricMapper(new ForwardConfig(), Jsonb.builder().build()));
  }

  private static AutoPlanTrigger disabledTrigger() {
    Config.setProperty("autoplan.enabled", "false");
    return new AutoPlanTrigger(new MessageService(), new GlobalPlanThresholds(100_000));
  }

  private static QueryPlanLogger disabledPlanLogger() {
    return new QueryPlanLogger();
  }

  private static IngestQueueConsumer consumer(CountingIngestMessage msg) {
    return new IngestQueueConsumer(new IngestQueue(), msg, disabledForwarder(), disabledTrigger(), disabledPlanLogger());
  }

  @Test
  void storeEnabledByDefault_callsIngest() throws Exception {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("plans.store.enabled", "true");
    var msg = new CountingIngestMessage();
    var c = consumer(msg);

    invokeIngestRequest(c, new MetricRequest());
    invokeIngestQueryPlans(c, new QueryPlanRequest());
    assertThat(msg.metricCalls.get()).isEqualTo(1);
    assertThat(msg.planCalls.get()).isEqualTo(1);
  }

  @Test
  void storeDisabled_skipsBothByDefault() throws Exception {
    Config.setProperty("metrics.store.enabled", "false");
    // plans.store.enabled unset → defaults to metrics.store.enabled (false)
    Config.clearProperty("plans.store.enabled");
    var msg = new CountingIngestMessage();
    var c = consumer(msg);

    invokeIngestRequest(c, new MetricRequest());
    invokeIngestQueryPlans(c, new QueryPlanRequest());
    assertThat(msg.metricCalls.get()).isZero();
    assertThat(msg.planCalls.get()).isZero();
  }

  @Test
  void plansEnabled_metricsDisabled_storesPlansOnly() throws Exception {
    Config.setProperty("metrics.store.enabled", "false");
    Config.setProperty("plans.store.enabled", "true");
    var msg = new CountingIngestMessage();
    var c = consumer(msg);

    invokeIngestRequest(c, new MetricRequest());
    invokeIngestQueryPlans(c, new QueryPlanRequest());
    assertThat(msg.metricCalls.get()).isZero();
    assertThat(msg.planCalls.get()).isEqualTo(1);
  }

  @Test
  void plansDisabled_metricsEnabled_storesMetricsOnly() throws Exception {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("plans.store.enabled", "false");
    var msg = new CountingIngestMessage();
    var c = consumer(msg);

    invokeIngestRequest(c, new MetricRequest());
    invokeIngestQueryPlans(c, new QueryPlanRequest());
    assertThat(msg.metricCalls.get()).isEqualTo(1);
    assertThat(msg.planCalls.get()).isZero();
  }

  private static void invokeIngestRequest(IngestQueueConsumer consumer, MetricRequest data) throws Exception {
    var m = IngestQueueConsumer.class.getDeclaredMethod("ingestRequest", MetricRequest.class);
    m.setAccessible(true);
    m.invoke(consumer, data);
  }

  private static void invokeIngestQueryPlans(IngestQueueConsumer consumer, QueryPlanRequest data) throws Exception {
    var m = IngestQueueConsumer.class.getDeclaredMethod("ingestQueryPlans", QueryPlanRequest.class);
    m.setAccessible(true);
    m.invoke(consumer, data);
  }
}
