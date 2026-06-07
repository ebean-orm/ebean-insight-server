package org.ebean.monitor.cli;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * Shared connection options. Either a static {@code --url} (e.g. an ingress) or,
 * when omitted, a supervised {@code kubectl port-forward} to the target Service.
 */
final class ConnectionOptions {

  @Option(names = "--url",
      description = "Static base URL (e.g. http://localhost:8091). When set, port-forward options are ignored.")
  @Nullable String url;

  @Option(names = "--namespace", defaultValue = "dev-core",
      description = "Kubernetes namespace (default: ${DEFAULT-VALUE}).")
  String namespace = "dev-core";

  @Option(names = "--service", defaultValue = "central-insight",
      description = "Kubernetes Service to port-forward to (default: ${DEFAULT-VALUE}).")
  String service = "central-insight";

  @Option(names = "--target-port", defaultValue = "8091",
      description = "Service port (default: ${DEFAULT-VALUE}).")
  int targetPort = 8091;

  @Option(names = "--local-port", defaultValue = "0",
      description = "Local port to bind; 0 picks a free ephemeral port.")
  int localPort;

  @Option(names = "--context", description = "kubectl context to use.")
  @Nullable String context;

  @Option(names = "--ready-timeout", defaultValue = "20",
      description = "Seconds to wait for the port-forward to become ready (default: ${DEFAULT-VALUE}).")
  long readySeconds = 20;
}
