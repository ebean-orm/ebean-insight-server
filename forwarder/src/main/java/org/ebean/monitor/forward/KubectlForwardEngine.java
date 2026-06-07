package org.ebean.monitor.forward;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * A {@link ForwardEngine} that supervises a {@code kubectl port-forward}
 * subprocess. Requires {@code kubectl} on the PATH and a working kubeconfig; auth
 * and authorisation are delegated entirely to the cluster (the EKS exec credential
 * plus {@code pods/portforward} RBAC).
 *
 * <p>Native-image friendly: only {@link ProcessBuilder}, regex and virtual threads
 * — no reflection, no Kubernetes client dependency.
 */
public final class KubectlForwardEngine implements ForwardEngine {

  // "Forwarding from 127.0.0.1:34567 -> 8091"
  static final Pattern FORWARDING =
      Pattern.compile("Forwarding from (\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d+) ->");

  // stderr fragments meaning "this forward is dead, respawn"
  private static final List<String> DROP_MARKERS = List.of(
      "lost connection to pod",
      "error upgrading connection",
      "an error occurred forwarding",
      "error forwarding port",
      "error copying from");

  // stderr fragment meaning "local port taken" -> supervisor re-picks a port
  private static final String BIND_CONFLICT_MARKER = "unable to listen on any of the requested ports";

  // stderr fragments meaning the failure is non-retryable (auth/config) -> fail fast.
  // Network reachability ("unable to connect to the server") is intentionally excluded — that may be transient.
  private static final List<String> FATAL_MARKERS = List.of(
      "token has expired",
      "error: getting credentials",
      "the server has asked for the client to provide credentials",
      "you must be logged in to the server",
      "error: you must be logged in",
      "unauthorized",
      "invalid configuration",
      "no configuration has been provided",
      "does not exist"); // e.g. error: context "foo" does not exist

  private final String kubectlBin;
  private final @Nullable String context;
  private final Duration readyTimeout;

  public KubectlForwardEngine(String kubectlBin, @Nullable String context, Duration readyTimeout) {
    this.kubectlBin = requireNonNull(kubectlBin, "kubectlBin");
    this.context = context;
    this.readyTimeout = requireNonNull(readyTimeout, "readyTimeout");
  }

  public KubectlForwardEngine() {
    this("kubectl", null, Duration.ofSeconds(10));
  }

  @Override
  public boolean selfResolves() {
    return true;
  }

  @Override
  public Upstream open(ForwardSpec spec) {
    var cmd = new ArrayList<String>();
    cmd.add(kubectlBin);
    if (context != null) {
      cmd.add("--context");
      cmd.add(context);
    }
    cmd.add("--namespace");
    cmd.add(spec.namespace());
    cmd.add("port-forward");
    cmd.add("--address");
    cmd.add("127.0.0.1");
    cmd.add(spec.kubectlRef());
    cmd.add(portMapping(spec));

    var pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(false); // keep stdout (Forwarding) and stderr (errors) distinct
    // inherits env so AWS_PROFILE / AWS_REGION / KUBECONFIG reach the eks get-token exec credential

    Process proc;
    try {
      proc = pb.start();
    } catch (IOException e) {
      throw new ForwardException("Failed to exec kubectl (is it on the PATH?)", e);
    }
    return new KubectlUpstream(proc, readyTimeout);
  }

  private static String portMapping(ForwardSpec spec) {
    return spec.preferredLocalPort() > 0
        ? spec.preferredLocalPort() + ":" + spec.targetPort()
        : ":" + spec.targetPort();
  }

  // --- stderr/stdout classification (package-private for testing) ----------

  static @Nullable InetSocketAddress parseForwarding(String line) {
    Matcher m = FORWARDING.matcher(line);
    if (m.find()) {
      return new InetSocketAddress(m.group(1), Integer.parseInt(m.group(2)));
    }
    return null;
  }

  static boolean isBindConflict(String line) {
    return line.toLowerCase(Locale.ROOT).contains(BIND_CONFLICT_MARKER);
  }

  static boolean isDropMarker(String line) {
    var lower = line.toLowerCase(Locale.ROOT);
    return DROP_MARKERS.stream().anyMatch(lower::contains);
  }

  static boolean isFatal(String line) {
    var lower = line.toLowerCase(Locale.ROOT);
    return FATAL_MARKERS.stream().anyMatch(lower::contains);
  }

  /** A single live {@code kubectl port-forward} process. */
  private static final class KubectlUpstream implements Upstream {

    private final Process proc;
    private final CompletableFuture<InetSocketAddress> bound = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Deque<String> stderrLines = new ArrayDeque<>();
    private volatile @Nullable ForwardException bindConflict;
    private volatile @Nullable Thread errPump;

    KubectlUpstream(Process proc, Duration readyTimeout) {
      this.proc = proc;
      pump(proc.inputReader(), this::onStdout, "kubectl-out");
      errPump = pump(proc.errorReader(), this::onStderr, "kubectl-err");

      proc.onExit().thenRun(() -> {
        if (closing.get()) {
          closed.complete(null);
          return;
        }
        // Give stderr a moment to drain so the cause (e.g. expired credentials) is captured.
        joinQuietly(errPump);
        var conflict = bindConflict;
        ForwardException ex = conflict != null
            ? conflict
            : new ForwardException(
                hasFatalStderr() ? ForwardException.Kind.FATAL : ForwardException.Kind.GENERIC,
                describeExit());
        if (!bound.isDone()) {
          bound.completeExceptionally(ex);
        }
        if (!closed.isDone()) {
          closed.completeExceptionally(ex);
        }
      });

      awaitReady(readyTimeout);
    }

    private void awaitReady(Duration timeout) {
      try {
        bound.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        close();
        var conflict = bindConflict;
        throw conflict != null ? conflict
            : new ForwardException("kubectl did not become ready within " + timeout);
      } catch (ExecutionException e) {
        close();
        var cause = e.getCause();
        throw cause instanceof ForwardException fe ? fe
            : new ForwardException("kubectl failed before ready", cause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        close();
        throw new ForwardException("Interrupted waiting for kubectl ready", e);
      }
    }

    private void onStdout(String line) {
      var addr = parseForwarding(line);
      if (addr != null && !bound.isDone()) {
        bound.complete(addr);
      }
    }

    private void onStderr(String line) {
      recordStderr(line);
      if (isBindConflict(line)) {
        var conflict = new ForwardException(ForwardException.Kind.BIND_CONFLICT, line);
        bindConflict = conflict;
        if (!bound.isDone()) {
          bound.completeExceptionally(conflict);
        }
      } else if (isDropMarker(line)) {
        if (!closed.isDone()) {
          closed.completeExceptionally(new ForwardException("forward dropped: " + line));
        }
        destroy();
      }
    }

    private void recordStderr(String line) {
      if (line.isBlank()) {
        return;
      }
      synchronized (stderrLines) {
        stderrLines.addLast(line.strip());
        while (stderrLines.size() > 8) {
          stderrLines.removeFirst();
        }
      }
    }

    /** The most recent (up to 3) stderr lines, or {@code null} if none. */
    private @Nullable String stderrTail() {
      synchronized (stderrLines) {
        if (stderrLines.isEmpty()) {
          return null;
        }
        var lines = new ArrayList<>(stderrLines);
        int from = Math.max(0, lines.size() - 3);
        return String.join(" | ", lines.subList(from, lines.size()));
      }
    }

    private boolean hasFatalStderr() {
      synchronized (stderrLines) {
        return stderrLines.stream().anyMatch(KubectlForwardEngine::isFatal);
      }
    }

    private String describeExit() {
      String base = "kubectl exited (code " + proc.exitValue() + ")";
      String tail = stderrTail();
      return tail == null ? base : base + ": " + tail;
    }

    private static void joinQuietly(@Nullable Thread thread) {
      if (thread == null) {
        return;
      }
      try {
        thread.join(Duration.ofMillis(500));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public InetSocketAddress localAddress() {
      var addr = bound.getNow(null);
      if (addr == null) {
        throw new ForwardException("forward not yet bound");
      }
      return addr;
    }

    @Override
    public CompletableFuture<Void> closed() {
      return closed;
    }

    @Override
    public void close() {
      closing.set(true);
      destroy();
    }

    private void destroy() {
      if (proc.isAlive()) {
        proc.destroy();
        try {
          if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          proc.destroyForcibly();
        }
      }
      if (!closed.isDone()) {
        closed.complete(null);
      }
    }

    private static Thread pump(BufferedReader reader, Consumer<String> onLine, String name) {
      return Thread.ofVirtual().name(name).start(() -> {
        try (reader) {
          String line;
          while ((line = reader.readLine()) != null) {
            onLine.accept(line);
          }
        } catch (IOException ignore) {
          // stream closed on process exit — handled via onExit()
        }
      });
    }
  }
}
