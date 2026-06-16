package org.ebean.monitor.forward;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.avaje.config.Config;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.api.MetricRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MetricForwarderIT {

  private HttpServer server;
  private MetricForwarder forwarder;
  private final Jsonb jsonb = Jsonb.builder().build();

  private MetricForwarder newForwarder() {
    var cfg = new ForwardConfig();
    return new MetricForwarder(cfg, new OtlpMetricMapper(cfg, jsonb));
  }

  @AfterEach
  void cleanup() {
    if (forwarder != null) {
      forwarder.stop();
    }
    if (server != null) {
      server.stop(0);
    }
    Config.setProperty("forward.otel.enabled", "false");
  }

  @Test
  void forwardsToHttpEndpoint() throws Exception {
    var bodyHolder = new AtomicReference<String>();
    var headerHolder = new AtomicReference<String>();
    var received = new CompletableFuture<Void>();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/metrics", (HttpExchange ex) -> {
      try (var is = ex.getRequestBody()) {
        bodyHolder.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
      }
      headerHolder.set(ex.getRequestHeaders().getFirst("X-Test-Header"));
      ex.sendResponseHeaders(200, -1);
      ex.close();
      received.complete(null);
    });
    server.start();
    int port = server.getAddress().getPort();

    Config.setProperty("forward.otel.enabled", "true");
    Config.setProperty("forward.otel.endpoint", "http://127.0.0.1:" + port);
    Config.setProperty("forward.otel.namespace", "eroad");
    Config.setProperty("forward.otel.headers.X-Test-Header", "abc");
    Config.setProperty("forward.otel.pollMillis", "20");

    forwarder = newForwarder();
    forwarder.start();

    var req = MetricRequest.builder()
      .eventTime(1_700_000_000_000L)
      .appName("myapp")
      .environment("test")
      .instanceId("pod-1")
      .version("1.0")
      .build();
    var md = MetricData.builder()
      .name("iud.User.save")
      .count(7L)
      .total(1234L)
      .max(999L)
      .build();
    req.metrics().add(md);

    forwarder.forward(req);

    received.get(5, TimeUnit.SECONDS);
    assertThat(bodyHolder.get()).contains("\"name\":\"ebean.dml.count\"");
    assertThat(bodyHolder.get()).contains("\"asInt\":\"7\"");
    assertThat(bodyHolder.get()).contains("\"stringValue\":\"eroad\"");
    assertThat(headerHolder.get()).isEqualTo("abc");
    long deadline = System.currentTimeMillis() + 2_000;
    while (forwarder.forwardedCount() == 0L && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertThat(forwarder.forwardedCount()).isEqualTo(1L);
  }

  @Test
  void disabled_doesNothing() {
    Config.setProperty("forward.otel.enabled", "false");
    forwarder = newForwarder();
    forwarder.start();
    var req = MetricRequest.builder().appName("x").build();
    forwarder.forward(req);
    assertThat(forwarder.forwardedCount()).isZero();
    assertThat(forwarder.droppedCount()).isZero();
  }

  @Test
  void overflow_dropsRequest() throws Exception {
    Config.setProperty("forward.otel.enabled", "true");
    Config.setProperty("forward.otel.endpoint", "http://127.0.0.1:1");
    Config.setProperty("forward.otel.queueSize", "1");
    Config.setProperty("forward.otel.connectTimeoutSeconds", "1");
    Config.setProperty("forward.otel.timeoutSeconds", "1");

    forwarder = newForwarder();
    // do NOT start the worker so the queue won't drain
    var req = MetricRequest.builder().appName("x").build();
    forwarder.forward(req);
    forwarder.forward(req);
    forwarder.forward(req);
    assertThat(forwarder.droppedCount()).isGreaterThanOrEqualTo(2L);
  }
}
