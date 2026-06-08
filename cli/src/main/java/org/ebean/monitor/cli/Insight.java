package org.ebean.monitor.cli;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import io.avaje.http.client.HttpClient;
import io.avaje.http.client.HttpClientRequest;
import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.http.client.RequestIntercept;
import org.ebean.monitor.forward.Endpoint;
import org.ebean.monitor.forward.ForwardTarget;
import org.ebean.monitor.forward.KubectlForwardEngine;
import org.ebean.monitor.forward.StaticEndpoint;
import org.ebean.monitor.forward.SupervisedForwarder;
import org.ebean.monitor.v1.httpclient.AppsApiHttpClient;
import org.ebean.monitor.v1.httpclient.EnvsApiHttpClient;
import org.ebean.monitor.v1.httpclient.MetricsApiHttpClient;
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
  final MetricsApiHttpClient metrics;

  private Insight(@Nullable SupervisedForwarder forwarder, HttpClient http) {
    this.forwarder = forwarder;
    this.plans = new PlansApiHttpClient(http);
    this.apps = new AppsApiHttpClient(http);
    this.envs = new EnvsApiHttpClient(http);
    this.metrics = new MetricsApiHttpClient(http);
  }

  /** Open a connection per the given options, starting a port-forward if needed. */
  static Insight open(ConnectionOptions conn) {
    conn.resolve();
    final URI base;
    final SupervisedForwarder forwarder;
    if (conn.hasUrl()) {
      Endpoint endpoint = new StaticEndpoint(conn.url());
      base = endpoint.baseUri();
      forwarder = null;
    } else {
      conn.requireForwardTarget();
      Optional<URI> shared = conn.noShared()
          ? Optional.empty()
          : new ForwardRegistry().discover(ForwardRegistry.targetKey(conn));
      if (shared.isPresent()) {
        base = shared.get();
        forwarder = null;
      } else {
        forwarder = SupervisedForwarder.builder()
            .target(ForwardTarget.service(conn.namespace(), conn.service(), conn.targetPort()))
            .localPort(conn.localPort())
            .engine(new KubectlForwardEngine("kubectl", conn.context(), Duration.ofSeconds(conn.readySeconds())))
            .build();
        base = forwarder.start(Duration.ofSeconds(conn.readySeconds()));
      }
    }

    HttpClient.Builder builder = HttpClient.builder()
        .baseUrl(base.toString())
        .bodyAdapter(new JsonbBodyAdapter());

    String key = conn.insightKey();
    if (key != null && !key.isBlank()) {
      String headerValue = key.trim();
      builder.requestIntercept(new RequestIntercept() {
        @Override
        public void beforeRequest(HttpClientRequest request) {
          request.header("Insight-Key", headerValue);
        }
      });
    }

    return new Insight(forwarder, builder.build());
  }

  @Override
  public void close() {
    if (forwarder != null) {
      forwarder.close();
    }
  }
}
