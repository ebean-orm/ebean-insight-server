package org.ebean.monitor.forward;

import io.avaje.config.Config;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the OTLP forwarder, read from {@code forward.otel.*} keys.
 */
@Singleton
public final class ForwardConfig {

  private final boolean enabled;
  private final String endpoint;
  private final String namespace;
  private final long reportingPeriodNanos;
  private final Duration timeout;
  private final Duration connectTimeout;
  private final int queueSize;
  private final long pollMillis;
  private final Map<String, String> headers;

  public ForwardConfig() {
    this.enabled = Config.getBool("forward.otel.enabled", false);
    this.endpoint = trimSlash(Config.get("forward.otel.endpoint", "http://localhost:4318"));
    this.namespace = Config.get("forward.otel.namespace", "");
    long periodSeconds = Config.getLong("forward.otel.reportingPeriodSeconds", 60);
    this.reportingPeriodNanos = periodSeconds * 1_000_000_000L;
    this.timeout = Duration.ofSeconds(Config.getLong("forward.otel.timeoutSeconds", 30));
    this.connectTimeout = Duration.ofSeconds(Config.getLong("forward.otel.connectTimeoutSeconds", 10));
    this.queueSize = Config.getInt("forward.otel.queueSize", 1024);
    this.pollMillis = Config.getLong("forward.otel.pollMillis", 200);
    this.headers = readHeaders();
  }

  private static Map<String, String> readHeaders() {
    var map = new LinkedHashMap<String, String>();
    for (String key : Config.asProperties().stringPropertyNames()) {
      if (key.startsWith("forward.otel.headers.")) {
        map.put(key.substring("forward.otel.headers.".length()), Config.get(key));
      }
    }
    return Map.copyOf(map);
  }

  private static String trimSlash(String url) {
    if (url == null) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public boolean enabled() {
    return enabled;
  }

  public String metricsUrl() {
    return endpoint + "/v1/metrics";
  }

  public String namespace() {
    return namespace;
  }

  public long reportingPeriodNanos() {
    return reportingPeriodNanos;
  }

  public Duration timeout() {
    return timeout;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public int queueSize() {
    return queueSize;
  }

  public long pollMillis() {
    return pollMillis;
  }

  public Map<String, String> headers() {
    return headers;
  }
}
