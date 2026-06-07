package org.ebean.monitor.cli;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * Shared connection options. Either a static {@code --url} (e.g. an ingress) or,
 * when omitted, a supervised {@code kubectl port-forward} to the target Service.
 *
 * <p>There are deliberately no built-in defaults for the deployment-specific
 * {@code --namespace} / {@code --service}: values come from an explicit flag or
 * from {@code ~/.insight/config.properties} (managed via {@code insight config}).
 * Resolution precedence is: explicit flag &gt; config file &gt; built-in default
 * (the latter only for non-identifying values such as the target port).
 */
final class ConnectionOptions {

  @Option(names = "--url",
      description = "Static base URL (e.g. http://localhost:8091). When set, port-forward options are ignored.")
  @Nullable String url;

  @Option(names = "--namespace",
      description = "Kubernetes namespace (or set with `insight config set namespace <ns>`).")
  @Nullable String namespace;

  @Option(names = "--service",
      description = "Kubernetes Service to port-forward to (or set with `insight config set service <svc>`).")
  @Nullable String service;

  @Option(names = "--target-port",
      description = "Service port (default: 8091).")
  @Nullable Integer targetPort;

  @Option(names = "--local-port",
      description = "Local port to bind; 0 picks a free ephemeral port (default: 0).")
  @Nullable Integer localPort;

  @Option(names = "--context", description = "kubectl context to use.")
  @Nullable String context;

  @Option(names = "--ready-timeout",
      description = "Seconds to wait for the port-forward to become ready (default: 20).")
  @Nullable Long readySeconds;

  @Option(names = "--no-shared",
      description = "Ignore any running `insight forward` daemon and start a private port-forward.")
  boolean noShared;

  @Option(names = "--insight-key", defaultValue = "${env:INSIGHT_KEY}",
      description = "API key sent as the Insight-Key header (falls back to the INSIGHT_KEY env var). "
          + "Not needed when reaching the server via port-forward.")
  @Nullable String insightKey;

  private boolean resolved;

  /** Fill any option not set on the command line from {@code ~/.insight/config.properties}. */
  void resolve() {
    resolve(new InsightConfig());
  }

  void resolve(InsightConfig config) {
    var props = config.load();
    if (isBlank(url)) {
      url = props.getProperty("url");
    }
    if (isBlank(namespace)) {
      namespace = props.getProperty("namespace");
    }
    if (isBlank(service)) {
      service = props.getProperty("service");
    }
    if (isBlank(context)) {
      context = props.getProperty("context");
    }
    if (isBlank(insightKey)) {
      insightKey = props.getProperty("insight-key");
    }
    if (targetPort == null) {
      targetPort = parseInt(props.getProperty("target-port"), 8091, "target-port");
    }
    if (localPort == null) {
      localPort = parseInt(props.getProperty("local-port"), 0, "local-port");
    }
    if (readySeconds == null) {
      readySeconds = parseLong(props.getProperty("ready-timeout"), 20L, "ready-timeout");
    }
    resolved = true;
  }

  /** True when a static base URL was supplied (flag or config). */
  boolean hasUrl() {
    return !isBlank(url);
  }

  String url() {
    if (isBlank(url)) {
      throw new IllegalStateException("url not set");
    }
    return url.trim();
  }

  /** Validate that a port-forward target (namespace + service) is available. */
  void requireForwardTarget() {
    ensureResolved();
    if (isBlank(namespace) || isBlank(service)) {
      throw new CliException("""
          No port-forward target configured.
            Pass --namespace <ns> and --service <svc>, or persist them:
              insight config set namespace <ns>
              insight config set service <svc>
            Alternatively connect directly with --url <baseUrl>.""");
    }
  }

  String namespace() {
    ensureResolved();
    return require(namespace, "namespace");
  }

  String service() {
    ensureResolved();
    return require(service, "service");
  }

  int targetPort() {
    ensureResolved();
    return targetPort != null ? targetPort : 8091;
  }

  int localPort() {
    ensureResolved();
    return localPort != null ? localPort : 0;
  }

  long readySeconds() {
    ensureResolved();
    return readySeconds != null ? readySeconds : 20L;
  }

  @Nullable String context() {
    return context;
  }

  @Nullable String insightKey() {
    return insightKey;
  }

  boolean noShared() {
    return noShared;
  }

  private void ensureResolved() {
    if (!resolved) {
      resolve();
    }
  }

  private static boolean isBlank(@Nullable String s) {
    return s == null || s.isBlank();
  }

  private static String require(@Nullable String s, String name) {
    if (isBlank(s)) {
      throw new IllegalStateException(name + " not set");
    }
    return s;
  }

  private static int parseInt(@Nullable String value, int fallback, String key) {
    if (isBlank(value)) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new CliException("config " + key + " is not a number: '" + value + "'");
    }
  }

  private static long parseLong(@Nullable String value, long fallback, String key) {
    if (isBlank(value)) {
      return fallback;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new CliException("config " + key + " is not a number: '" + value + "'");
    }
  }
}
