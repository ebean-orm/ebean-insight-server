package org.ebean.monitor.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

/**
 * A short-lived loopback HTTP server that receives the OAuth2 authorization-code
 * redirect on {@code http://localhost:<port>/callback}.
 *
 * <p>Started before the browser is opened; {@link #await(Duration)} blocks until
 * Cognito redirects back with a {@code code} (and {@code state}) or an
 * {@code error}. Always {@link #close() closed} to release the port.
 */
final class LoopbackReceiver implements AutoCloseable {

  /** The query parameters captured from the redirect request. */
  record CallbackResult(
      @Nullable String code,
      @Nullable String state,
      @Nullable String error,
      @Nullable String errorDescription) {
  }

  private final HttpServer server;
  private final BlockingQueue<CallbackResult> queue = new ArrayBlockingQueue<>(1);

  private LoopbackReceiver(HttpServer server) {
    this.server = server;
  }

  /** Bind and start a receiver on the loopback interface at {@code port}. */
  static LoopbackReceiver start(int port) {
    try {
      HttpServer server = HttpServer.create(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
      LoopbackReceiver receiver = new LoopbackReceiver(server);
      server.createContext("/callback", receiver::handle);
      server.createContext("/", receiver::handle);
      server.setExecutor(null);
      server.start();
      return receiver;
    } catch (IOException e) {
      throw new CliException("Could not start the loopback receiver on port " + port
          + " (" + e.getMessage() + "). Set a free port with `insight config set auth-redirect-ports <port>`"
          + " (it must also be a registered callback URL).");
    }
  }

  /**
   * Try each port in order and return the first receiver that binds successfully.
   * Port {@code 0} lets the OS pick a free ephemeral port (RFC 8252 §7.3 —
   * requires an RFC-compliant auth server such as Entra ID).
   */
  static LoopbackReceiver startFirst(int... ports) {
    CliException last = null;
    for (int port : ports) {
      try {
        return start(port);
      } catch (CliException e) {
        last = e;
      }
    }
    throw last != null ? last
        : new CliException("No loopback ports available. Set free ports with "
            + "`insight config set auth-redirect-ports <ports>`.");
  }

  /** The actual bound port (useful when started on an ephemeral port). */
  int port() {
    return server.getAddress().getPort();
  }

  /** Block for up to {@code timeout} for the redirect, or null when none arrives. */
  @Nullable CallbackResult await(Duration timeout) {    try {
      return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
    CallbackResult result = new CallbackResult(
        params.get("code"), params.get("state"),
        params.get("error"), params.get("error_description"));

    boolean ok = result.code() != null && result.error() == null;
    String detail = result.error() != null
        ? escape(result.error()) + (result.errorDescription() != null ? " — " + escape(result.errorDescription()) : "")
        : "No authorization code was returned.";
    String body = ok ? successPage() : errorPage(detail);

    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(ok ? 200 : 400, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
    queue.offer(result);
  }

  private static Map<String, String> parseQuery(@Nullable String rawQuery) {
    Map<String, String> map = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return map;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
        String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        map.put(key, value);
      }
    }
    return map;
  }

  private static String successPage() {
    return page(
        "Signed in",
        "#16a34a",
        "<path d=\"M20 6 9 17l-5-5\"/>",
        "Signed in to insight",
        "Authentication complete. You can close this tab and return to your terminal.",
        true);
  }

  private static String errorPage(String detail) {
    return page(
        "Sign-in failed",
        "#dc2626",
        "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M15 9l-6 6M9 9l6 6\"/>",
        "Sign-in failed",
        detail,
        false);
  }

  private static String page(String title, String accent, String iconPaths,
      String heading, String message, boolean autoClose) {
    String closeScript = autoClose
        ? "<script>setTimeout(function(){window.close();},2500);</script>"
        : "";
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>__TITLE__</title>
        <style>
          :root { color-scheme: light dark; }
          * { box-sizing: border-box; }
          body {
            margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background: radial-gradient(1200px 600px at 50% -10%, #eef2ff, #f8fafc 60%);
            color: #0f172a;
          }
          @media (prefers-color-scheme: dark) {
            body { background: radial-gradient(1200px 600px at 50% -10%, #1e293b, #0b1220 60%); color: #e2e8f0; }
            .card { background: #0f172a; border-color: #1e293b; }
            .brand { color: #94a3b8; }
            .msg { color: #94a3b8; }
          }
          .card {
            width: 92%; max-width: 460px; padding: 40px 36px; text-align: center;
            background: #ffffff; border: 1px solid #e2e8f0; border-radius: 16px;
            box-shadow: 0 10px 40px rgba(2, 6, 23, 0.12);
          }
          .icon {
            width: 64px; height: 64px; margin: 0 auto 20px; border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
            background: __ACCENT__1a; color: __ACCENT__;
          }
          .icon svg { width: 34px; height: 34px; fill: none; stroke: currentColor; stroke-width: 2.5; stroke-linecap: round; stroke-linejoin: round; }
          h1 { margin: 0 0 10px; font-size: 22px; font-weight: 650; }
          .msg { margin: 0; font-size: 15px; line-height: 1.5; color: #475569; }
          .brand {
            margin-top: 28px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
            font-size: 13px; letter-spacing: 0.04em; color: #64748b;
          }
        </style>
        </head>
        <body>
          <div class="card">
            <div class="icon"><svg viewBox="0 0 24 24">__ICON__</svg></div>
            <h1>__HEADING__</h1>
            <p class="msg">__MESSAGE__</p>
            <div class="brand">insight</div>
          </div>
          __CLOSE__
        </body>
        </html>
        """
        .replace("__TITLE__", title)
        .replace("__ACCENT__", accent)
        .replace("__ICON__", iconPaths)
        .replace("__HEADING__", heading)
        .replace("__MESSAGE__", message)
        .replace("__CLOSE__", closeScript);
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
