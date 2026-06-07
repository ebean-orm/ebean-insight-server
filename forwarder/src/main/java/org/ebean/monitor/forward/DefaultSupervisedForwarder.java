package org.ebean.monitor.forward;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Default {@link SupervisedForwarder}. Owns a stable local port pinned for the
 * forwarder's lifetime; the upstream forward to a specific pod is the disposable
 * resource that is rebuilt on drop. A single virtual thread runs the supervision
 * loop.
 */
final class DefaultSupervisedForwarder implements SupervisedForwarder {

  private static final System.Logger log = System.getLogger("org.ebean.monitor.forward");

  private final ForwardTarget target;
  private final ForwardEngine engine;
  private final BackoffPolicy backoff;
  private final HealthCheck health;
  private final Duration healthTimeout;
  private final Consumer<ForwardStatus> listener;
  private final int configuredLocalPort;

  private final Object lock = new Object();
  private final List<CompletableFuture<URI>> readyWaiters = new ArrayList<>();
  private final CompletableFuture<URI> firstReady = new CompletableFuture<>();

  private volatile ForwardStatus status = new ForwardStatus(ForwardState.STOPPED, null, 0, null);
  private volatile @Nullable URI baseUri;
  private volatile int localPort;
  private volatile boolean running;
  private volatile ForwardEngine.@Nullable Upstream current;
  // the most recent transient (retryable) error — used for timeout diagnostics
  private volatile @Nullable Throwable lastError;
  // a non-retryable error that aborted supervision — terminal
  private volatile @Nullable Throwable terminalFailure;
  private @Nullable Thread supervisor;

  DefaultSupervisedForwarder(Builder b) {
    this.target = b.target;
    this.engine = b.engine;
    this.backoff = b.backoff;
    this.health = b.healthCheck;
    this.healthTimeout = b.healthTimeout;
    this.listener = b.onStatus;
    this.configuredLocalPort = b.localPort;
  }

  @Override
  public URI start(Duration readyTimeout) {
    synchronized (lock) {
      if (!running) {
        running = true;
        localPort = configuredLocalPort != 0 ? configuredLocalPort : pickFreePort();
        baseUri = localUri(localPort);
        supervisor = Thread.ofVirtual().name("insight-forwarder").start(this::superviseLoop);
      }
    }
    try {
      return firstReady.get(readyTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      var f = lastError;
      close();
      throw f != null
          ? new ForwardException("Forward failed before becoming ready: " + messageOf(f), f)
          : new ForwardException("Timed out waiting for forward READY after " + readyTimeout);
    } catch (ExecutionException e) {
      close();
      var cause = e.getCause();
      throw cause instanceof ForwardException fe
          ? fe
          : new ForwardException("Forward failed before becoming ready: " + messageOf(cause), cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      close();
      throw new ForwardException("Interrupted starting forward", e);
    }
  }

  private void superviseLoop() {
    int failures = 0;
    while (running) {
      try {
        transition(ForwardState.STARTING, failures, null);
        var spec = new ForwardSpec(target.kubectlRef(), target.namespace(), target.targetPort(), localPort);
        try (var up = engine.open(spec)) {
          current = up;
          if (!waitHealthy()) {
            failures = backoffSleep("health check failed", failures, null);
            continue;
          }
          failures = 0;
          transition(ForwardState.READY, 0, null);
          firstReady.complete(requireBaseUri());
          completeReadyWaiters();
          up.closed().join(); // blocks until the forward drops or is closed
        } finally {
          current = null;
        }
        if (running) {
          transition(ForwardState.RECONNECTING, failures, null);
        }
      } catch (ForwardException e) {
        if (e.kind() == ForwardException.Kind.BIND_CONFLICT) {
          localPort = pickFreePort();
          baseUri = localUri(localPort);
          transition(ForwardState.RECONNECTING, failures, e);
          continue; // a port clash is not a real failure — retry immediately
        }
        if (e.kind() == ForwardException.Kind.FATAL) {
          abort(e);
          break; // non-retryable (auth/config) — fail fast, don't burn the retry budget
        }
        if (!running) {
          break;
        }
        failures = backoffSleep("forward error", failures, e);
      } catch (CompletionException e) {
        var fatal = fatalCause(e);
        if (fatal != null) {
          abort(fatal);
          break;
        }
        if (!running) {
          break;
        }
        failures = backoffSleep("forward dropped", failures, e.getCause());
      } catch (RuntimeException e) {
        if (!running) {
          break;
        }
        failures = backoffSleep("unexpected error", failures, e);
      }
    }
    if (status.state() != ForwardState.FAILED) {
      transition(ForwardState.STOPPED, failures, terminalFailure != null ? terminalFailure : lastError);
    }
  }

  private boolean waitHealthy() {
    var deadline = Instant.now().plus(healthTimeout);
    var uri = baseUri;
    if (uri == null) {
      return false;
    }
    while (running && Instant.now().isBefore(deadline)) {
      if (health.isHealthy(uri)) {
        return true;
      }
      sleep(Duration.ofMillis(100));
    }
    return false;
  }

  private int backoffSleep(String reason, int failures, @Nullable Throwable err) {
    int attempt = failures + 1;
    this.lastError = err;
    log.log(System.Logger.Level.DEBUG, "forward reconnect ({0}) attempt {1}", reason, attempt);
    transition(ForwardState.RECONNECTING, attempt, err);
    sleep(backoff.nextDelay(attempt));
    return attempt;
  }

  private void abort(Throwable err) {
    this.terminalFailure = err;
    running = false;
    transition(ForwardState.FAILED, status.reconnectCount(), err);
    firstReady.completeExceptionally(err);
    failReadyWaiters(err);
  }

  /** Unwraps Completion/Execution wrappers to find a non-retryable (FATAL) {@link ForwardException}. */
  private static @Nullable ForwardException fatalCause(@Nullable Throwable t) {
    for (int i = 0; i < 8 && t != null; i++) {
      if (t instanceof ForwardException fe && fe.kind() == ForwardException.Kind.FATAL) {
        return fe;
      }
      t = t.getCause();
    }
    return null;
  }

  private void transition(ForwardState state, int reconnects, @Nullable Throwable err) {
    var snap = new ForwardStatus(state, state == ForwardState.READY ? baseUri : status.baseUri(), reconnects, err);
    status = snap;
    try {
      listener.accept(snap);
    } catch (RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "forward status listener threw", e);
    }
  }

  private void completeReadyWaiters() {
    List<CompletableFuture<URI>> waiters;
    URI uri;
    synchronized (lock) {
      uri = baseUri;
      waiters = List.copyOf(readyWaiters);
      readyWaiters.clear();
    }
    for (var w : waiters) {
      w.complete(uri);
    }
  }

  @Override
  public URI baseUri() {
    return requireBaseUri();
  }

  @Override
  public boolean isReady() {
    return status.state() == ForwardState.READY;
  }

  @Override
  public CompletableFuture<URI> awaitReady(Duration budget) {
    synchronized (lock) {
      if (!running) {
        var f = currentError();
        return CompletableFuture.failedFuture(
            f != null ? f : new ForwardException("forwarder is not running"));
      }
      if (status.state() == ForwardState.READY && baseUri != null) {
        return CompletableFuture.completedFuture(baseUri);
      }
      var future = new CompletableFuture<URI>();
      readyWaiters.add(future);
      return future;
    }
  }

  @Override
  public ForwardStatus status() {
    return status;
  }

  @Override
  public void close() {
    running = false;
    var up = current;
    if (up != null) {
      up.close();
    }
    var t = supervisor;
    if (t != null) {
      t.interrupt();
    }
    firstReady.completeExceptionally(new ForwardException("forwarder closed"));
    failReadyWaiters(new ForwardException("forwarder closed"));
  }

  private void failReadyWaiters(Throwable err) {
    List<CompletableFuture<URI>> waiters;
    synchronized (lock) {
      waiters = List.copyOf(readyWaiters);
      readyWaiters.clear();
    }
    for (var w : waiters) {
      w.completeExceptionally(err);
    }
  }

  private @Nullable Throwable currentError() {
    return terminalFailure != null ? terminalFailure : lastError;
  }

  private URI requireBaseUri() {
    var uri = baseUri;
    if (uri == null) {
      throw new ForwardException("forwarder not started");
    }
    return uri;
  }

  private static URI localUri(int port) {
    return URI.create("http://127.0.0.1:" + port);
  }

  private static int pickFreePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new ForwardException("Unable to allocate a free local port", e);
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(Math.max(0, d.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String messageOf(Throwable t) {
    String m = t.getMessage();
    return m != null ? m : t.toString();
  }
}
