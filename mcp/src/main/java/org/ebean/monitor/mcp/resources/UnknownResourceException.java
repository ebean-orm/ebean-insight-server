package org.ebean.monitor.mcp.resources;

/** Thrown when {@code resources/read} names a URI that is not a valid plan resource. */
public class UnknownResourceException extends RuntimeException {
  public UnknownResourceException(String uri) {
    super("Unknown resource: " + uri);
  }
}
