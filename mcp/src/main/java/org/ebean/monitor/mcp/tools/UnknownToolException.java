package org.ebean.monitor.mcp.tools;

/** Thrown when {@code tools/call} names a tool that does not exist. */
public class UnknownToolException extends RuntimeException {
  public UnknownToolException(String name) {
    super("Unknown tool: " + name);
  }
}
