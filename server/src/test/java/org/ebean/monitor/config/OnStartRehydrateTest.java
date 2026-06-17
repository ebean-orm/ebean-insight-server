package org.ebean.monitor.config;

import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DCaptureRequest;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.ingest.PlanShapeBackfill;
import org.ebean.monitor.web.MessageService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OnStart#pushPendingCaptures}: verifies that pending
 * capture requests are re-pushed into {@link MessageService} correctly on
 * startup, covering both env-specific and any-env cases.
 */
class OnStartRehydrateTest {

  /** No-op backfill stub — PlanShapeBackfill.run() is never called by pushPendingCaptures. */
  private static final class NoopBackfill extends PlanShapeBackfill {
    NoopBackfill() { super(null); }
    @Override public int run() { return 0; }
  }

  private final MessageService messageService = new MessageService();
  private final OnStart onStart = new OnStart(new NoopBackfill(), messageService);

  private static MetricRequest poll(String app, String env) {
    return MetricRequest.builder().appName(app).environment(env).build();
  }

  private static DCaptureRequest request(String appName, String envName, String hash) {
    DApp app = new DApp(appName);
    DEnv env = envName != null ? new DEnv(envName) : null;
    return new DCaptureRequest(app, hash)
      .setEnv(env)
      .setRequestedAt(Instant.now());
  }

  @Test
  void envSpecific_rehydratedAndDeliveredToCorrectEnv() {
    var r = request("myapp", "test", "abc123");

    int count = onStart.pushPendingCaptures(List.of(r));

    assertThat(count).isEqualTo(1);
    assertThat(messageService.pendingResponse()).isTrue();
    // delivered to the matching env
    assertThat(messageService.responseBody(poll("myapp", "test"))).isEqualTo("v1|qp:abc123");
    // queue is now drained
    assertThat(messageService.pendingResponse()).isFalse();
  }

  @Test
  void anyEnv_nullEnvOnRequest_usesAnyEnvSentinel() {
    var r = request("myapp", null, "def456");

    onStart.pushPendingCaptures(List.of(r));

    // any-env bucket: delivered regardless of which env polls
    assertThat(messageService.responseBody(poll("myapp", "prod"))).isEqualTo("v1|qp:def456");
  }

  @Test
  void multipleRequests_allRehydrated() {
    var r1 = request("app1", "dev", "hash1");
    var r2 = request("app1", "dev", "hash2");
    var r3 = request("app2", null, "hash3");

    int count = onStart.pushPendingCaptures(List.of(r1, r2, r3));

    assertThat(count).isEqualTo(3);
    String body = messageService.responseBody(poll("app1", "dev"));
    assertThat(body).contains("qp:hash1").contains("qp:hash2");
    assertThat(messageService.responseBody(poll("app2", "test"))).isEqualTo("v1|qp:hash3");
  }

  @Test
  void emptyList_returnsZero_noMessagesQueued() {
    int count = onStart.pushPendingCaptures(List.of());

    assertThat(count).isEqualTo(0);
    assertThat(messageService.pendingResponse()).isFalse();
  }
}
