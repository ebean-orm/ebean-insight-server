package org.ebean.monitor.cli;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import org.ebean.monitor.forward.ForwardTarget;
import org.ebean.monitor.forward.KubectlForwardEngine;
import org.ebean.monitor.forward.SupervisedForwarder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Run a long-lived supervised {@code kubectl port-forward}. While it runs, other
 * {@code insight} commands automatically reuse the same tunnel (via
 * {@link ForwardRegistry}) instead of spinning up their own per command.
 */
@Command(name = "forward", aliases = {"daemon"}, mixinStandardHelpOptions = true,
    description = "Hold a supervised port-forward open for other insight commands to reuse.")
final class ForwardCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();

  @Option(names = "--no-register",
      description = "Do not advertise this forward to other insight commands.")
  boolean noRegister;

  @Override
  public Integer call() throws Exception {
    var forwarder = SupervisedForwarder.builder()
        .target(ForwardTarget.service(conn.namespace, conn.service, conn.targetPort))
        .localPort(conn.localPort)
        .engine(new KubectlForwardEngine("kubectl", conn.context, Duration.ofSeconds(conn.readySeconds)))
        .onStatus(s -> System.out.println("[" + s.state() + "]"
            + (s.lastError() != null ? " " + s.lastError().getMessage() : "")))
        .build();

    var registry = new ForwardRegistry();
    String target = ForwardRegistry.targetKey(conn);
    var stopped = new CountDownLatch(1);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (!noRegister) {
        registry.clear();
      }
      forwarder.close();
      stopped.countDown();
    }, "insight-forward-shutdown"));

    URI base = forwarder.start(Duration.ofSeconds(conn.readySeconds));
    if (!noRegister) {
      registry.write(base, ProcessHandle.current().pid(), target);
    }

    System.out.println("ebean-insight port-forward ready: " + base + "  (" + target + ")");
    if (!noRegister) {
      System.out.println("Other `insight` commands will use this automatically.");
    }
    System.out.println("Press Ctrl-C to stop.");

    stopped.await();
    return 0;
  }
}
