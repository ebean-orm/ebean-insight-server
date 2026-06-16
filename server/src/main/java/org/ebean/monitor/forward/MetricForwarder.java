package org.ebean.monitor.forward;

import io.avaje.inject.PostConstruct;
import io.avaje.inject.PreDestroy;
import jakarta.inject.Singleton;
import org.ebean.monitor.api.MetricRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forwards each ingested {@link MetricRequest} to an OTLP/HTTP JSON endpoint
 * (e.g. Grafana Alloy {@code /v1/metrics}). Disabled by default; controlled via
 * {@link ForwardConfig}.
 *
 * <p>Failure isolation: enqueueing is non-blocking; on overflow the request is
 * dropped and the {@code dropped} counter is incremented. Network errors are
 * logged but never propagate to the caller, so the storage path and the
 * query-plan back-channel response remain unaffected.
 *
 * <p>Temporality: emits {@code AGGREGATION_TEMPORALITY_DELTA}. Insert
 * {@code otelcol.processor.deltatocumulative} into the Alloy pipeline if the
 * downstream backend (e.g. Mimir) requires cumulative.
 */
@Singleton
public final class MetricForwarder {

  private static final Logger log = LoggerFactory.getLogger(MetricForwarder.class);

  private final ForwardConfig config;
  private final OtlpMetricMapper mapper;
  private final BlockingQueue<MetricRequest> queue;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong forwarded = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  private volatile HttpClient httpClient;
  private volatile Thread worker;
  private volatile boolean running;

  public MetricForwarder(ForwardConfig config, OtlpMetricMapper mapper) {
    this.config = config;
    this.mapper = mapper;
    this.queue = new ArrayBlockingQueue<>(Math.max(1, config.queueSize()));
  }

  @PostConstruct
  void start() {
    if (!config.enabled()) {
      log.info("OTLP forwarder disabled (forward.otel.enabled=false)");
      return;
    }
    log.info("OTLP forwarder enabled, endpoint={} queueSize={}", config.metricsUrl(), config.queueSize());
    httpClient = HttpClient.newBuilder()
      .connectTimeout(config.connectTimeout())
      .build();
    running = true;
    worker = new Thread(this::drainLoop, "otlp-forwarder");
    worker.setDaemon(true);
    worker.start();
  }

  @PreDestroy
  void stop() {
    running = false;
    var t = worker;
    if (t != null) {
      t.interrupt();
      try {
        t.join(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    log.info("OTLP forwarder stopped, forwarded={} failed={} dropped={}", forwarded.get(), failed.get(), dropped.get());
  }

  /**
   * Enqueue a metric request for forwarding. Non-blocking. Returns immediately
   * if the forwarder is disabled or the queue is full.
   */
  public void forward(MetricRequest request) {
    if (!config.enabled() || request == null) {
      return;
    }
    if (!queue.offer(request)) {
      long n = dropped.incrementAndGet();
      if ((n & 0xff) == 1) {
        log.warn("OTLP forwarder queue full, dropping request (total dropped={})", n);
      }
    }
  }

  long droppedCount() {
    return dropped.get();
  }

  long forwardedCount() {
    return forwarded.get();
  }

  long failedCount() {
    return failed.get();
  }

  private void drainLoop() {
    long pollMillis = config.pollMillis();
    while (running) {
      try {
        MetricRequest req = queue.poll(pollMillis, TimeUnit.MILLISECONDS);
        if (req != null) {
          send(req);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        log.error("Unexpected error in OTLP forwarder loop", e);
      }
    }
  }

  private void send(MetricRequest req) {
    String body;
    try {
      body = mapper.build(req, config.reportingPeriodNanos());
    } catch (Exception e) {
      failed.incrementAndGet();
      log.error("Failed to build OTLP payload for {}/{}", req.appName(), req.environment(), e);
      return;
    }
    try {
      var builder = HttpRequest.newBuilder()
        .uri(URI.create(config.metricsUrl()))
        .timeout(config.timeout())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      for (Map.Entry<String, String> h : config.headers().entrySet()) {
        builder.header(h.getKey(), h.getValue());
      }
      HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int code = resp.statusCode();
      if (code >= 200 && code < 300) {
        forwarded.incrementAndGet();
        if (log.isTraceEnabled()) {
          log.trace("OTLP forwarded app={} env={} status={}", req.appName(), req.environment(), code);
        }
      } else {
        long n = failed.incrementAndGet();
        if ((n & 0x3f) == 1) {
          log.warn("OTLP forward non-2xx status={} body={} (total failed={})", code, truncate(resp.body()), n);
        }
      }
    } catch (Exception e) {
      long n = failed.incrementAndGet();
      if ((n & 0x3f) == 1) {
        log.warn("OTLP forward error: {} (total failed={})", e.toString(), n);
      }
    }
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 256 ? s.substring(0, 256) + "..." : s;
  }
}
