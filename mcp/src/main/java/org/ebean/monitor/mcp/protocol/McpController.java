package org.ebean.monitor.mcp.protocol;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.jex.http.Context;

import java.util.Optional;

/**
 * MCP Streamable-HTTP endpoint: {@code POST /mcp}.
 * <p>
 * Accepts a single JSON-RPC message (read from the raw request body). Requests
 * receive a JSON-RPC response as {@code application/json}; notifications (no
 * {@code id}) receive {@code 202 Accepted} with no body. The endpoint sits
 * behind the bearer-auth filter (it is not under the permitted {@code /health}
 * prefix).
 * <p>
 * The dynamic JSON-RPC body is handled via {@link Context} (mirroring the
 * server's ingest controller) rather than typed parameters, so no typed client
 * is generated — callers use the protocol directly.
 */
@Controller
@Path("/mcp")
public class McpController {

  private final McpJsonRpc jsonRpc;

  public McpController(McpJsonRpc jsonRpc) {
    this.jsonRpc = jsonRpc;
  }

  @Post
  void handle(Context ctx) {
    Optional<String> response = jsonRpc.handle(ctx.body());
    if (response.isEmpty()) {
      ctx.status(202).write("");
      return;
    }
    ctx.status(200).contentType("application/json");
    ctx.write(response.get());
  }
}
