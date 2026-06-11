package org.ebean.monitor.mcp.auth;

import io.avaje.jex.Jex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that a registered {@link BearerAuthFilter} actually enforces
 * auth on a matched route over real HTTP (proving the Jex {@code filter(...)}
 * registration works, not just the filter logic in isolation).
 */
class BearerAuthFilterHttpTest {

  private static Jex.Server server;
  private static int port;
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @BeforeAll
  static void start() {
    TokenStore store = new TokenStore("claude:s3cr3t");
    server = Jex.create()
        .port(0)
        .filter(new BearerAuthFilter(store, "/open"))
        .get("/mcp", ctx -> ctx.text("hello"))
        .get("/open/ping", ctx -> ctx.text("pong"))
        .start();
    port = server.port();
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void protectedRoute_noToken_401() throws Exception {
    assertThat(get("/mcp", null).statusCode()).isEqualTo(401);
  }

  @Test
  void protectedRoute_wrongToken_401() throws Exception {
    assertThat(get("/mcp", "wrong").statusCode()).isEqualTo(401);
  }

  @Test
  void protectedRoute_validToken_200() throws Exception {
    HttpResponse<String> r = get("/mcp", "s3cr3t");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("hello");
  }

  @Test
  void permittedPrefix_noToken_proceeds() throws Exception {
    HttpResponse<String> r = get("/open/ping", null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("pong");
  }
}
