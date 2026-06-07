package org.ebean.monitor.forward;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisedForwarderTest {

  private static ForwardEngine.Upstream upstream(int port, CompletableFuture<Void> closed) {
    return new ForwardEngine.Upstream() {
      @Override public InetSocketAddress localAddress() {
        return new InetSocketAddress("127.0.0.1", port);
      }

      @Override public CompletableFuture<Void> closed() {
        return closed;
      }

      @Override public void close() {
        closed.complete(null);
      }
    };
  }

  @Test
  void startsAndBecomesReady() throws Exception {
    var staysOpen = new CompletableFuture<Void>();
    ForwardEngine engine = spec -> upstream(spec.preferredLocalPort(), staysOpen);

    try (var fwd = SupervisedForwarder.builder()
        .target(ForwardTarget.service("ns", "svc", 8091))
        .engine(engine)
        .healthCheck(uri -> true)
        .build()) {

      var base = fwd.start(Duration.ofSeconds(2));
      assertThat(base.getScheme()).isEqualTo("http");
      assertThat(base.getPort()).isGreaterThan(0);
      assertThat(fwd.isReady()).isTrue();
      assertThat(fwd.awaitReady(Duration.ofSeconds(1)).get(1, SECONDS)).isEqualTo(base);
      assertThat(fwd.status().state()).isEqualTo(ForwardState.READY);
    }
  }

  @Test
  void reconnectsAfterInitialFailure() {
    var opens = new AtomicInteger();
    var staysOpen = new CompletableFuture<Void>();
    ForwardEngine engine = spec -> {
      if (opens.incrementAndGet() == 1) {
        throw new ForwardException("first attempt fails");
      }
      return upstream(spec.preferredLocalPort(), staysOpen);
    };

    try (var fwd = SupervisedForwarder.builder()
        .target(ForwardTarget.service("ns", "svc", 8091))
        .engine(engine)
        .healthCheck(uri -> true)
        .backoff(attempt -> Duration.ZERO)
        .build()) {

      fwd.start(Duration.ofSeconds(2));
      assertThat(opens.get()).isGreaterThanOrEqualTo(2);
      assertThat(fwd.isReady()).isTrue();
      assertThat(fwd.status().reconnectCount()).isEqualTo(0);
    }
  }

  @Test
  void awaitReady_failsAfterClose() {
    var staysOpen = new CompletableFuture<Void>();
    ForwardEngine engine = spec -> upstream(spec.preferredLocalPort(), staysOpen);

    var fwd = SupervisedForwarder.builder()
        .target(ForwardTarget.service("ns", "svc", 8091))
        .engine(engine)
        .healthCheck(uri -> true)
        .build();
    fwd.start(Duration.ofSeconds(2));
    fwd.close();

    assertThat(fwd.awaitReady(Duration.ofSeconds(1)))
        .isCompletedExceptionally();
  }

  @Test
  void fatalError_abortsImmediately_withoutBurningRetryBudget() {
    var opens = new AtomicInteger();
    ForwardEngine engine = spec -> {
      opens.incrementAndGet();
      throw new ForwardException(ForwardException.Kind.FATAL,
          "kubectl exited (code 255): Token has expired and refresh failed");
    };

    try (var fwd = SupervisedForwarder.builder()
        .target(ForwardTarget.service("ns", "svc", 8091))
        .engine(engine)
        .healthCheck(uri -> true)
        // a huge backoff: if the loop retried instead of aborting, start() would block
        // until its 5s ready-timeout rather than returning quickly.
        .backoff(attempt -> Duration.ofSeconds(30))
        .build()) {

      var startNanos = System.nanoTime();
      assertThatThrownBy(() -> fwd.start(Duration.ofSeconds(5)))
          .isInstanceOf(ForwardException.class)
          .hasMessageContaining("Token has expired");
      var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

      assertThat(elapsedMs).isLessThan(2000);                 // failed fast, not after the 5s timeout
      assertThat(opens.get()).isEqualTo(1);                   // no retry attempts
      assertThat(fwd.status().state()).isEqualTo(ForwardState.FAILED);
    }
  }
}
