package org.ebean.monitor.cli;

import java.net.URI;
import java.time.Duration;

import io.avaje.http.client.HttpClient;
import io.avaje.http.client.JsonbBodyAdapter;
import org.ebean.monitor.forward.Endpoint;
import org.ebean.monitor.forward.ForwardTarget;
import org.ebean.monitor.forward.KubectlForwardEngine;
import org.ebean.monitor.forward.StaticEndpoint;
import org.ebean.monitor.forward.SupervisedForwarder;
import org.ebean.monitor.v1.httpclient.AppsApiHttpClient;
import org.ebean.monitor.v1.httpclient.EnvsApiHttpClient;
import org.ebean.monitor.v1.httpclient.PlansApiHttpClient;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the server {@link Endpoint} (static URL or supervised port-forward),
 * builds the typed API clients over it, and owns the forwarder lifecycle.
 */
final class Insight implements AutoCloseable {

  private final @Nullable SupervisedForwarder forwarder;
  final PlansApiHttpClient plans;
  final AppsApiHttpClient apps;
  final EnvsApiHttpClient envs;

  private Insight(@Nullable SupervisedForwarder forwarder, HttpClient http) {
    this.forwarder = forwarder;
    this.plans = new PlansApiHttpClient(http);
    this.apps = new AppsApiHttpClient(http);
    this.envs = new EnvsApiHttpClient(http);
  }

  /** Open a connection per the given options, starting a port-forward if needed. */
  static Insight open(ConnectionOptions conn) {
    final URI base;
    final SupervisedForwarder forwarder;
    if (conn.url != null && !conn.url.isBlank()) {
      Endpoint endpoint = new StaticEndpoint(conn.url.trim());
      base = endpoint.baseUri();
      forwarder = null;
    } else {
      forwarder = SupervisedForwarder.builder()
          .target(ForwardTarget.service(conn.namespace, conn.service, conn.targetPort))
          .localPort(conn.localPort)
          .engine(new KubectlForwardEngine("kubectl", conn.context, Duration.ofSeconds(conn.readySeconds)))
          .build();
      base = forwarder.start(Duration.ofSeconds(conn.readySeconds));
    }

    HttpClient http = HttpClient.builder()
        .baseUrl(base.toString())
        .bodyAdapter(new JsonbBodyAdapter())
        .build();
    return new Insight(forwarder, http);
  }

  @Override
  public void close() {
    if (forwarder != null) {
      forwarder.close();
    }
  }
}
