package org.ebean.monitor.forward;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * A reachable base address for the ebean-insight API, decoupled from how it is
 * reached (a supervised port-forward, or a static ingress URL).
 *
 * <p>This is the seam that lets an API client treat a port-forward respawn as a
 * brief pause: on a transport failure the client re-reads {@link #baseUri()} and
 * waits on {@link #awaitReady(Duration)} for the endpoint to become reachable
 * again, rather than failing outright.
 */
public interface Endpoint {

  /**
   * The current base URI, for example {@code http://127.0.0.1:34567}. May change
   * across reconnects if the local port had to be re-picked, so callers should
   * re-read it per attempt rather than caching it.
   */
  URI baseUri();

  /** True if the endpoint is currently reachable. */
  boolean isReady();

  /**
   * Returns a future that completes with the current {@link #baseUri()} once the
   * endpoint is READY, or completes exceptionally if it has permanently failed or
   * been closed. The caller bounds the wait with the supplied budget.
   */
  CompletableFuture<URI> awaitReady(Duration budget);
}
