package org.ebean.monitor.forward;

import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.api.MetricRequestBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpMetricMapperTest {

  private static final long EVENT_TIME = 1_700_000_000_000L;

  private final Jsonb jsonb = Jsonb.builder().build();

  private OtlpMetricMapper mapper(String namespace) {
    io.avaje.config.Config.setProperty("forward.otel.namespace", namespace);
    return new OtlpMetricMapper(new ForwardConfig(), jsonb);
  }

  private static MetricData timer(String name, long count, long total, long max) {
    return MetricData.builder()
      .name(name)
      .count(count)
      .total(total)
      .max(max)
      .build();
  }

  private static MetricData gauge(String name, double value) {
    return MetricData.builder()
      .name(name)
      .value(value)
      .build();
  }

  private static MetricRequestBuilder baseRequest() {
    return MetricRequest.builder()
      .eventTime(EVENT_TIME)
      .appName("myapp")
      .environment("test")
      .instanceId("pod-1")
      .version("1.2.3");
  }

  @Test
  void resourceAttributes() {
    var req = baseRequest().build();
    String json = mapper("eroad").build(req, 60_000_000_000L);
    assertThat(json).contains("\"key\":\"service.name\"", "\"stringValue\":\"myapp\"");
    assertThat(json).contains("\"key\":\"deployment.environment.name\"", "\"stringValue\":\"test\"");
    assertThat(json).contains("\"key\":\"service.instance.id\"", "\"stringValue\":\"pod-1\"");
    assertThat(json).contains("\"key\":\"service.version\"", "\"stringValue\":\"1.2.3\"");
    assertThat(json).contains("\"key\":\"service.namespace\"", "\"stringValue\":\"eroad\"");
  }

  @Test
  void emitsCountTotalMaxForTimer() {
    var req = baseRequest().build();
    req.metrics().add(timer("iud.User.save", 5, 1234, 999));
    String json = mapper("").build(req, 60_000_000_000L);

    assertThat(json).contains("\"name\":\"ebean.dml.count\"");
    assertThat(json).contains("\"name\":\"ebean.dml.total\"");
    assertThat(json).contains("\"name\":\"ebean.dml.max\"");
    assertThat(json).contains("\"unit\":\"us\"");
    assertThat(json).contains("\"asInt\":\"5\"", "\"asInt\":\"1234\"", "\"asInt\":\"999\"");
    assertThat(json).contains("\"aggregationTemporality\":1");
    assertThat(json).contains("\"isMonotonic\":true");
    assertThat(json).contains("\"key\":\"label\"", "\"stringValue\":\"User.save\"");
  }

  @Test
  void emitsGaugeForValue() {
    var req = baseRequest().build();
    req.metrics().add(gauge("jvm.memory.heap.used", 12345.0));
    String json = mapper("").build(req, 60_000_000_000L);

    assertThat(json).contains("\"name\":\"jvm.memory.heap.used\"");
    assertThat(json).contains("\"gauge\":{");
    assertThat(json).contains("\"asDouble\":12345.0");
    assertThat(json).doesNotContain("\"sum\":");
  }

  @Test
  void emitsDbAttributeOnDbMetrics() {
    var req = baseRequest().build();
    var db = MetricDbData.builder().db("main").build();
    db.metrics().add(timer("orm.User.find", 3, 600, 300));
    req.dbs().add(db);
    String json = mapper("").build(req, 60_000_000_000L);

    assertThat(json).contains("\"name\":\"ebean.query.count\"");
    assertThat(json).contains("\"key\":\"type\"", "\"stringValue\":\"orm\"");
    assertThat(json).contains("\"key\":\"label\"", "\"stringValue\":\"User.find\"");
    assertThat(json).contains("\"key\":\"db\"", "\"stringValue\":\"main\"");
  }

  @Test
  void timestampsUseEventTime() {
    var req = baseRequest().build();
    req.metrics().add(timer("iud.X", 1, 2, 3));
    long periodNanos = 60_000_000_000L;
    String json = mapper("").build(req, periodNanos);

    long endNano = req.eventTime() * 1_000_000L;
    long startNano = endNano - periodNanos;
    assertThat(json).contains("\"timeUnixNano\":\"" + endNano + "\"");
    assertThat(json).contains("\"startTimeUnixNano\":\"" + startNano + "\"");
  }

  @Test
  void startEventTimePrefersClientWindow() {
    var req = baseRequest().startEventTime(EVENT_TIME - 30_000L).build(); // disjoint 30s window from client
    req.metrics().add(timer("iud.X", 1, 2, 3));
    long periodNanos = 60_000_000_000L; // would synthesise 60s window if startEventTime ignored
    String json = mapper("").build(req, periodNanos);

    long endNano = req.eventTime() * 1_000_000L;
    long startNano = req.startEventTime() * 1_000_000L;
    assertThat(json).contains("\"timeUnixNano\":\"" + endNano + "\"");
    assertThat(json).contains("\"startTimeUnixNano\":\"" + startNano + "\"");
    // sanity: not the synthesised 60s window
    assertThat(json).doesNotContain("\"startTimeUnixNano\":\"" + (endNano - periodNanos) + "\"");
  }

  @Test
  void startEventTimeFallsBackToReportingPeriodWhenZero() {
    var req = baseRequest().build();
    req.metrics().add(timer("iud.X", 1, 2, 3));
    long periodNanos = 60_000_000_000L;
    String json = mapper("").build(req, periodNanos);

    long endNano = req.eventTime() * 1_000_000L;
    long startNano = endNano - periodNanos;
    assertThat(json).contains("\"startTimeUnixNano\":\"" + startNano + "\"");
  }

  @Test
  void skipsEmptyMetric() {
    var req = baseRequest().build();
    var empty = MetricData.builder().name("x").build();
    req.metrics().add(empty);
    String json = mapper("").build(req, 60_000_000_000L);
    assertThat(json).contains("\"metrics\":[]");
  }

  @Test
  void escapesQuotesAndBackslashesInValues() {
    var req = baseRequest().appName("my\"app\\").build();
    String json = mapper("").build(req, 60_000_000_000L);
    assertThat(json).contains("\"stringValue\":\"my\\\"app\\\\\"");
  }

  @Test
  void resAttrsForwardedAsResourceAttributes() {
    var req = baseRequest().build();
    req.resAttrs().put("business.domain", "ingestion");
    req.resAttrs().put("business.system", "consolidation");
    String json = mapper("").build(req, 60_000_000_000L);
    assertThat(json).contains("\"key\":\"business.domain\"", "\"stringValue\":\"ingestion\"");
    assertThat(json).contains("\"key\":\"business.system\"", "\"stringValue\":\"consolidation\"");
  }

  @Test
  void resAttrsCannotOverrideReservedFields() {
    var req = baseRequest().build();
    req.resAttrs().put("service.name", "hijacked");
    req.resAttrs().put("service.version", "9.9.9");
    req.resAttrs().put("service.instance.id", "fake");
    req.resAttrs().put("deployment.environment.name", "prod");
    String json = mapper("").build(req, 60_000_000_000L);
    // reserved field values come from the dedicated header fields
    assertThat(json).contains("\"stringValue\":\"myapp\"");
    assertThat(json).contains("\"stringValue\":\"1.2.3\"");
    assertThat(json).contains("\"stringValue\":\"pod-1\"");
    assertThat(json).contains("\"stringValue\":\"test\"");
    // and the hijacking values are NOT emitted
    assertThat(json).doesNotContain("\"stringValue\":\"hijacked\"");
    assertThat(json).doesNotContain("\"stringValue\":\"9.9.9\"");
  }

  @Test
  void clientNamespaceWinsOverServerDefault() {
    var req = baseRequest().build();
    req.resAttrs().put("service.namespace", "fromClient");
    String json = mapper("fromServer").build(req, 60_000_000_000L);
    assertThat(json).contains("\"stringValue\":\"fromClient\"");
    assertThat(json).doesNotContain("\"stringValue\":\"fromServer\"");
  }
}
