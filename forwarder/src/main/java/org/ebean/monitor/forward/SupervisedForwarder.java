package org.ebean.monitor.forward;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Maintains a port-forward to the ebean-insight server, exposing a stable local
 * {@link Endpoint} that survives pod churn by transparently re-establishing the
 * forward and re-resolving a Ready pod.
 *
 * <pre>{@code
 *
 *   try (var fwd = SupervisedForwarder.builder()
 *           .target(ForwardTarget.service("dev-core", "ebean-insight", 8091))
 *           .build()) {
 *
 *     URI base = fwd.start(Duration.ofSeconds(15));   // blocks until READY
 *     var api = InsightApiClient.create(base);        // targets the stable localhost URI
 *     ...
 *   }
 *
 * }</pre>
 */
public interface SupervisedForwarder extends Endpoint, AutoCloseable {

  /**
   * Start the supervision loop and block until the forward first becomes READY.
   *
   * @param readyTimeout maximum time to wait for the first READY state
   * @return the stable local base URI
   * @throws ForwardException if it does not become ready within the timeout
   */
  URI start(Duration readyTimeout);

  /** The current status snapshot. */
  ForwardStatus status();

  @Override
  void close();

  static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for a {@link SupervisedForwarder}. */
  final class Builder {

    @Nullable ForwardTarget target;
    @Nullable ForwardEngine engine;
    int localPort;
    BackoffPolicy backoff = BackoffPolicy.defaults();
    HealthCheck healthCheck = new TcpHealthCheck();
    Duration healthTimeout = Duration.ofSeconds(5);
    Consumer<ForwardStatus> onStatus = s -> { };

    Builder() {
    }

    /** The forward target (Service or Deployment). Required. */
    public Builder target(ForwardTarget target) {
      this.target = target;
      return this;
    }

    /**
     * The engine used to establish each forward. Defaults to a
     * {@link KubectlForwardEngine} using {@code kubectl} on the PATH.
     */
    public Builder engine(ForwardEngine engine) {
      this.engine = engine;
      return this;
    }

    /**
     * Pin the local port (kept stable across reconnects). {@code 0} (default)
     * picks a free ephemeral port once at start.
     */
    public Builder localPort(int localPort) {
      this.localPort = localPort;
      return this;
    }

    public Builder backoff(BackoffPolicy backoff) {
      this.backoff = backoff;
      return this;
    }

    public Builder healthCheck(HealthCheck healthCheck) {
      this.healthCheck = healthCheck;
      return this;
    }

    public Builder healthTimeout(Duration healthTimeout) {
      this.healthTimeout = healthTimeout;
      return this;
    }

    /** Listener notified on every status transition (invoked best-effort). */
    public Builder onStatus(Consumer<ForwardStatus> onStatus) {
      this.onStatus = onStatus;
      return this;
    }

    public SupervisedForwarder build() {
      requireNonNull(target, "target is required");
      if (engine == null) {
        engine = new KubectlForwardEngine();
      }
      return new DefaultSupervisedForwarder(this);
    }
  }
}
