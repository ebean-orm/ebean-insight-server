package org.ebean.monitor.ingest;

import io.avaje.config.Config;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.forward.ForwardConfig;
import org.ebean.monitor.forward.MetricForwarder;
import org.ebean.monitor.forward.OtlpMetricMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IngestQueueConsumerStoreFlagTest {

  private static class CountingIngestMessage extends IngestMessage {
    final AtomicInteger calls = new AtomicInteger();

    CountingIngestMessage() {
      super(null, null, null);
    }

    @Override
    public void ingest(MetricRequest request) {
      calls.incrementAndGet();
    }
  }

  @AfterEach
  void resetConfig() {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("forward.otel.enabled", "false");
  }

  private static MetricForwarder disabledForwarder() {
    Config.setProperty("forward.otel.enabled", "false");
    return new MetricForwarder(new ForwardConfig(), new OtlpMetricMapper(new ForwardConfig(), Jsonb.builder().build()));
  }

  @Test
  void storeEnabledByDefault_callsIngest() throws Exception {
    Config.setProperty("metrics.store.enabled", "true");
    var msg = new CountingIngestMessage();
    var consumer = new IngestQueueConsumer(new IngestQueue(), msg, disabledForwarder());

    invokeIngestRequest(consumer, new MetricRequest());
    assertThat(msg.calls.get()).isEqualTo(1);
  }

  @Test
  void storeDisabled_skipsIngest() throws Exception {
    Config.setProperty("metrics.store.enabled", "false");
    var msg = new CountingIngestMessage();
    var consumer = new IngestQueueConsumer(new IngestQueue(), msg, disabledForwarder());

    invokeIngestRequest(consumer, new MetricRequest());
    assertThat(msg.calls.get()).isZero();
  }

  private static void invokeIngestRequest(IngestQueueConsumer consumer, MetricRequest data) throws Exception {
    var m = IngestQueueConsumer.class.getDeclaredMethod("ingestRequest", MetricRequest.class);
    m.setAccessible(true);
    m.invoke(consumer, data);
  }
}
