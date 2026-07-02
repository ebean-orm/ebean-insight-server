package org.ebean.monitor.mcp.auth;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import io.avaje.jex.http.Context;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the OAuth2 protected-resource discovery endpoint.
 *
 * <p>Integration tests (via {@code @InjectTest}) verify HTTP-level behaviour
 * against the fully wired server. Unit tests exercise the JSON-building logic
 * directly via a fake {@link Context} without needing to boot the server or
 * depend on avaje-config startup state.
 */
@InjectTest
class OAuthMetadataControllerTest {

  @Inject HttpClient httpClient;

  // --- integration: HTTP-level ---

  @Test
  void returnsNotFound_whenIssuerNotConfigured() {
    // application-test.yaml has no mcp.auth.issuer → 404 expected.
    HttpResponse<String> res = httpClient.request()
        .path(".well-known").path("oauth-protected-resource")
        .GET().asString();

    assertThat(res.statusCode()).isEqualTo(404);
  }

  @Test
  void isAccessibleWithoutAuthToken() {
    // /.well-known is on the permit list — no Bearer token should be required.
    // 404 is acceptable (no issuer configured), but never 401.
    HttpResponse<String> res = httpClient.request()
        .path(".well-known").path("oauth-protected-resource")
        .GET().asString();

    assertThat(res.statusCode()).isNotEqualTo(401);
  }

  // --- unit: JSON metadata building ---

  @Test
  void buildsJsonMetadata_withIssuerAndClientId() {
    var config = new McpOAuthConfig("https://idp.example.com/pool", "client-abc");
    var controller = new OAuthMetadataController(config);
    var ctx = new FakeContext("https", "mcp.example.com");

    controller.oauthProtectedResource(ctx.proxy());

    assertThat(ctx.status).isEqualTo(200);
    assertThat(ctx.contentType).contains("application/json");
    assertThat(ctx.body)
        .contains("\"resource\":\"https://mcp.example.com/mcp\"")
        .contains("\"authorization_servers\":[\"https://idp.example.com/pool\"]")
        .contains("\"bearer_methods_supported\":[\"header\"]")
        .contains("\"scopes_supported\":[\"openid\"]")
        .contains("\"client_id\":\"client-abc\"");
  }

  @Test
  void buildsJsonMetadata_withoutClientId() {
    var config = new McpOAuthConfig("https://idp.example.com/pool", null);
    var ctx = new FakeContext("https", "mcp.example.com");

    new OAuthMetadataController(config).oauthProtectedResource(ctx.proxy());

    assertThat(ctx.body).doesNotContain("client_id");
    assertThat(ctx.status).isEqualTo(200);
  }

  @Test
  void returnsNotFound_whenIssuerBlank() {
    var config = new McpOAuthConfig("", null);
    var ctx = new FakeContext("https", "mcp.example.com");

    new OAuthMetadataController(config).oauthProtectedResource(ctx.proxy());

    assertThat(ctx.status).isEqualTo(404);
  }

  @Test
  void resourceUrlDerivedFromRequestHostAndScheme() {
    var config = new McpOAuthConfig("https://idp.example.com/pool", null);
    var ctx = new FakeContext("http", "localhost:8092");

    new OAuthMetadataController(config).oauthProtectedResource(ctx.proxy());

    assertThat(ctx.body).contains("\"resource\":\"http://localhost:8092/mcp\"");
  }

  @Test
  void resourceUrl_usesXForwardedProto_whenPresent() {
    var config = new McpOAuthConfig("https://idp.example.com/pool", null);
    // Simulate pod behind ingress: raw scheme is http but X-Forwarded-Proto says https.
    var ctx = new FakeContext("http", "mcp.example.com")
        .withRequestHeader("X-Forwarded-Proto", "https");

    new OAuthMetadataController(config).oauthProtectedResource(ctx.proxy());

    assertThat(ctx.body).contains("\"resource\":\"https://mcp.example.com/mcp\"");
  }

  // --- fake context ---

  private static final class FakeContext {
    int status;
    String contentType;
    String body;
    private final String scheme;
    private final String host;
    private final Map<String, String> requestHeaders = new HashMap<>();

    FakeContext(String scheme, String host) {
      this.scheme = scheme;
      this.host = host;
    }

    FakeContext withRequestHeader(String name, String value) {
      requestHeaders.put(name, value);
      return this;
    }

    Context proxy() {
      InvocationHandler h = (p, method, args) -> switch (method.getName()) {
        case "scheme" -> scheme;
        case "host" -> host;
        case "header" -> args.length == 1 ? requestHeaders.get(args[0]) : p;
        case "status" -> { status = (int) args[0]; yield p; }
        case "contentType" -> { contentType = (String) args[0]; yield p; }
        case "write" -> { body = (String) args[0]; yield null; }
        case "writeEmpty" -> { status = (int) args[0]; yield null; }
        case "toString" -> "FakeContext";
        case "hashCode" -> System.identityHashCode(p);
        case "equals" -> p == args[0];
        default -> throw new UnsupportedOperationException("Unexpected: " + method.getName());
      };
      return (Context) Proxy.newProxyInstance(
          Context.class.getClassLoader(), new Class<?>[]{Context.class}, h);
    }
  }
}
