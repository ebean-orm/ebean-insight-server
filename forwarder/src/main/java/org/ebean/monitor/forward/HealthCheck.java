package org.ebean.monitor.forward;

import java.net.URI;

/** Probes whether the forwarded endpoint is actually reachable. */
@FunctionalInterface
public interface HealthCheck {

  /** True if the endpoint at {@code baseUri} is reachable right now. */
  boolean isHealthy(URI baseUri);
}
