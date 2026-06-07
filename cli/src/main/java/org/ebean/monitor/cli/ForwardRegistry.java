package org.ebean.monitor.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * Tiny on-disk advert for a running {@code insight forward} daemon.
 *
 * <p>The daemon writes its stable local base URI (plus pid and target) here; short
 * CLI commands read it and reuse the tunnel instead of spinning up their own. The
 * advert is only honoured when the requested target matches and the daemon is both
 * alive and reachable, otherwise it is treated as stale and removed.
 */
final class ForwardRegistry {

  private final Path file;

  ForwardRegistry() {
    this(defaultFile());
  }

  ForwardRegistry(Path file) {
    this.file = file;
  }

  static Path defaultFile() {
    String home = System.getProperty("user.home", ".");
    return Path.of(home, ".insight", "forward.properties");
  }

  static String targetKey(ConnectionOptions conn) {
    return conn.namespace() + "/" + conn.service() + ":" + conn.targetPort();
  }

  void write(URI base, long pid, String target) throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    var props = new Properties();
    props.setProperty("baseUri", base.toString());
    props.setProperty("pid", Long.toString(pid));
    props.setProperty("target", target);
    props.setProperty("startedAt", Instant.now().toString());
    try (var out = Files.newOutputStream(file)) {
      props.store(out, "ebean-insight forward daemon");
    }
  }

  /** Return a reachable, live daemon URI for the given target, else empty. */
  Optional<URI> discover(String wantTarget) {
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    var props = new Properties();
    try (var in = Files.newInputStream(file)) {
      props.load(in);
    } catch (IOException e) {
      return Optional.empty();
    }
    String url = props.getProperty("baseUri");
    if (url == null || !wantTarget.equals(props.getProperty("target"))) {
      return Optional.empty();
    }
    String pid = props.getProperty("pid");
    if (pid != null && !pidAlive(pid)) {
      clear();
      return Optional.empty();
    }
    final URI base;
    try {
      base = URI.create(url);
    } catch (RuntimeException e) {
      clear();
      return Optional.empty();
    }
    if (!reachable(base)) {
      clear();
      return Optional.empty();
    }
    return Optional.of(base);
  }

  void clear() {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignore) {
      // best effort
    }
  }

  private static boolean pidAlive(String pid) {
    try {
      return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean reachable(URI base) {
    String host = base.getHost() == null ? "127.0.0.1" : base.getHost();
    int port = base.getPort() < 0 ? 80 : base.getPort();
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 300);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
