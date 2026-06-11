package org.ebean.monitor.mcp.tools;

import java.util.Map;

/**
 * A callable MCP tool: its advertised name/description/input schema and the
 * handler that executes it.
 * <p>
 * The handler returns the tool result already serialised as a JSON string
 * (the tool owns serialisation since it knows its concrete return type); the
 * {@link InsightTools} wraps that into the MCP {@code tools/call} content block.
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema, Handler handler) {

  @FunctionalInterface
  public interface Handler {
    /**
     * Execute the tool.
     *
     * @param arguments the {@code arguments} object from {@code tools/call}
     *                  (may be {@code null} when the caller sent none).
     * @return the result serialised as a JSON string.
     */
    String handle(Map<String, Object> arguments) throws Exception;
  }
}
