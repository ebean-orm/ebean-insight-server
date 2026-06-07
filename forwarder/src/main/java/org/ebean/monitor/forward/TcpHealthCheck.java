package org.ebean.monitor.forward;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;

/**
 * Default {@link HealthCheck}: a plain TCP connect to the forwarded local port.
 * Cheap and dependency-free — confirms the forward is bound and accepting
 * connections without assuming anything about the HTTP layer.
 */
public final class TcpHealthCheck implements HealthCheck {

  private final int timeoutMillis;

  public TcpHealthCheck(Duration timeout) {
    this.timeoutMillis = (int) Math.max(1, timeout.toMillis());
  }

  public TcpHealthCheck() {
    this(Duration.ofSeconds(2));
  }

  @Override
  public boolean isHealthy(URI baseUri) {
    var host = baseUri.getHost();
    var port = baseUri.getPort();
    if (host == null || port < 0) {
      return false;
    }
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
