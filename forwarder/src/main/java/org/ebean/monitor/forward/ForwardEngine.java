package org.ebean.monitor.forward;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Establishes a single port-forward to a target. Pluggable so the native CLI can
 * use the lightweight {@link KubectlForwardEngine} while a JVM build could swap in
 * a richer Kubernetes-client based engine.
 */
public interface ForwardEngine {

  /**
   * Open one forward; the returned {@link Upstream} serves a local endpoint until
   * it drops or is closed.
   *
   * @throws ForwardException if the forward cannot be established
   */
  Upstream open(ForwardSpec spec);

  /**
   * True if the engine resolves a Ready pod itself (e.g. kubectl targeting a
   * Service/Deployment), letting the supervisor skip separate pod resolution.
   */
  default boolean selfResolves() {
    return false;
  }

  /** A single live forward bound to a local address. */
  interface Upstream extends AutoCloseable {

    /** The local address the forward is bound to. */
    InetSocketAddress localAddress();

    /**
     * Completes when the forward drops: normally if it was closed on request, or
     * exceptionally if the underlying connection/pod was lost.
     */
    CompletableFuture<Void> closed();

    @Override
    void close();
  }
}
