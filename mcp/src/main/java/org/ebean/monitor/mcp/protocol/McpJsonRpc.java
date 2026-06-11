package org.ebean.monitor.mcp.protocol;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import jakarta.inject.Singleton;
import org.ebean.monitor.mcp.tools.InsightTools;
import org.ebean.monitor.mcp.tools.UnknownToolException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal JSON-RPC 2.0 dispatcher implementing the MCP {@code initialize}
 * handshake and {@code ping}, hand-rolled over avaje-jsonb (no Jackson/Reactor).
 * <p>
 * Dynamic JSON-RPC shapes ({@code id}, {@code params}, {@code result}) are read
 * and written as {@code Map}s via avaje-jsonb's built-in adapters, which are
 * reflection-free and GraalVM-native safe.
 * <p>
 * {@link #handle(String)} returns the response JSON for requests, or empty for
 * notifications (which receive no JSON-RPC response — the HTTP layer answers
 * 202 Accepted). Tool and resource methods are added in later phases.
 */
@Singleton
public class McpJsonRpc {

  static final String JSONRPC_VERSION = "2.0";
  static final int PARSE_ERROR = -32700;
  static final int INVALID_REQUEST = -32600;
  static final int METHOD_NOT_FOUND = -32601;
  static final int INVALID_PARAMS = -32602;

  private final McpServer server;
  private final InsightTools tools;
  private final JsonType<Map<String, Object>> mapType;

  @SuppressWarnings({"unchecked", "rawtypes"})
  public McpJsonRpc(McpServer server, InsightTools tools, Jsonb jsonb) {
    this.server = server;
    this.tools = tools;
    this.mapType = (JsonType) jsonb.type(Map.class);
  }

  /**
   * Handle a single JSON-RPC message.
   *
   * @return the response JSON, or {@link Optional#empty()} for a notification
   *         (no {@code id}) which warrants no response.
   */
  public Optional<String> handle(String body) {
    Map<String, Object> request;
    try {
      request = mapType.fromJson(body);
    } catch (RuntimeException e) {
      return Optional.of(error(null, PARSE_ERROR, "Parse error"));
    }

    Object id = request.get("id");
    if (id == null) {
      // JSON-RPC notification (e.g. notifications/initialized) — no response.
      return Optional.empty();
    }
    id = normaliseId(id);

    Object methodValue = request.get("method");
    if (!(methodValue instanceof String method)) {
      return Optional.of(error(id, INVALID_REQUEST, "Invalid Request"));
    }

    return Optional.of(dispatch(id, method, asMap(request.get("params"))));
  }

  private String dispatch(Object id, String method, Map<String, Object> params) {
    return switch (method) {
      case "initialize" -> result(id, server.initializeResult(params));
      case "ping" -> result(id, new LinkedHashMap<>());
      case "tools/list" -> result(id, toolsList());
      case "tools/call" -> toolsCall(id, params);
      default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
    };
  }

  private Map<String, Object> toolsList() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tools", tools.definitions());
    return result;
  }

  private String toolsCall(Object id, Map<String, Object> params) {
    if (params == null || !(params.get("name") instanceof String name)) {
      return error(id, INVALID_PARAMS, "Missing tool name");
    }
    try {
      return result(id, tools.call(name, asMap(params.get("arguments"))));
    } catch (UnknownToolException e) {
      return error(id, INVALID_PARAMS, e.getMessage());
    }
  }

  private String result(Object id, Object result) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("jsonrpc", JSONRPC_VERSION);
    envelope.put("id", id);
    envelope.put("result", result);
    return mapType.toJson(envelope);
  }

  private String error(Object id, int code, String message) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("code", code);
    err.put("message", message);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("jsonrpc", JSONRPC_VERSION);
    envelope.put("id", id);
    envelope.put("error", err);
    return mapType.toJson(envelope);
  }

  /** Echo integral numeric ids back as integers (not e.g. 1.0). */
  private static Object normaliseId(Object id) {
    if (id instanceof Number n && !(id instanceof Long) && !(id instanceof Integer)) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d)) {
        return (long) d;
      }
    }
    return id;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : null;
  }
}
