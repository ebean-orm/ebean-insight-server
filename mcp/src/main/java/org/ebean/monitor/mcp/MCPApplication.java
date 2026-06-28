package org.ebean.monitor.mcp;

import io.avaje.jex.AvajeJex;

/**
 * Entry point for the ebean-insight MCP server.
 * <p>
 * Starts an avaje-jex HTTP server wired from the avaje-inject context (port from
 * {@code server.port}). The server exposes the Model Context Protocol over HTTP
 * so AI agents can drive the ebean-insight {@code /v1} API; routes are
 * contributed by {@code JexPlugin} beans discovered in the context.
 */
public class MCPApplication {

  public static void main(String[] args) {
    // set before AvajeJex.start() triggers DI wiring of the Jsonb bean; captured
    // query plans can exceed avaje-jsonb's default 50k char string-buffer limit
    System.setProperty("jsonb.parserMaxStringBuffer", "2000000");
    AvajeJex.start();
  }
}
