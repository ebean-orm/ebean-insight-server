package org.ebean.monitor.mcp.protocol;

import io.avaje.config.Config;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Core MCP server logic: protocol-version negotiation, advertised capabilities
 * and server identity for the {@code initialize} handshake.
 * <p>
 * Transport/JSON-RPC concerns live in {@link McpJsonRpc}; this class is pure
 * (no I/O) so it is straightforward to unit test.
 */
@Singleton
public class McpServer {

  /** Latest MCP protocol version this server prefers. */
  static final String LATEST_PROTOCOL = "2025-06-18";

  /** Protocol versions this server understands (negotiated against the client). */
  private static final Set<String> SUPPORTED_PROTOCOLS =
      Set.of("2025-06-18", "2025-03-26", "2024-11-05");

  private final String name;
  private final String version;

  public McpServer() {
    this.name = Config.get("mcp.server.name", Config.get("app.name", "ebean-insight-mcp"));
    this.version = Config.get("mcp.server.version", "0.1.0");
  }

  /**
   * Build the {@code initialize} result: the negotiated protocol version, the
   * server capabilities, and server identity.
   * <p>
   * Advertises the {@code tools} and {@code resources} capabilities (neither
   * {@code subscribe} nor {@code listChanged} — captures are read on demand).
   */
  Map<String, Object> initializeResult(Map<String, Object> params) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("protocolVersion", negotiateProtocol(params));
    result.put("capabilities", capabilities());
    result.put("serverInfo", serverInfo());
    return result;
  }

  private Map<String, Object> capabilities() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("tools", new LinkedHashMap<>());
    caps.put("resources", new LinkedHashMap<>());
    return caps;
  }

  private String negotiateProtocol(Map<String, Object> params) {
    Object requested = params == null ? null : params.get("protocolVersion");
    if (requested instanceof String s && SUPPORTED_PROTOCOLS.contains(s)) {
      return s;
    }
    return LATEST_PROTOCOL;
  }

  private Map<String, Object> serverInfo() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("name", name);
    info.put("version", version);
    return info;
  }
}
