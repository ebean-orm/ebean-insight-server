package org.ebean.monitor.forward;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * An {@link Endpoint} for a fixed URL, for example a public ingress address.
 *
 * <p>Always ready; {@link #awaitReady(Duration)} completes immediately. Lets the
 * same API client run against a static URL or a supervised port-forward without
 * code changes.
 */
public final class StaticEndpoint implements Endpoint {

  private final URI baseUri;

  public StaticEndpoint(URI baseUri) {
    this.baseUri = requireNonNull(baseUri, "baseUri");
  }

  public StaticEndpoint(String baseUri) {
    this(URI.create(baseUri));
  }

  @Override
  public URI baseUri() {
    return baseUri;
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public CompletableFuture<URI> awaitReady(Duration budget) {
    return CompletableFuture.completedFuture(baseUri);
  }
}
