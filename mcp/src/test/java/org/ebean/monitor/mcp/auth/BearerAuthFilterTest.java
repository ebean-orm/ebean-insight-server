package org.ebean.monitor.mcp.auth;

import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;
import io.avaje.oauth2.core.data.AccessToken;
import io.avaje.oauth2.core.jwt.JwtVerifier;
import io.avaje.oauth2.core.jwt.JwtVerifyException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BearerAuthFilterTest {

  private static BearerAuthFilter filter(TokenStore store) {
    return new BearerAuthFilter(store, null, "/health");
  }

  private static BearerAuthFilter filter(TokenStore store, JwtVerifier jwtVerifier) {
    return new BearerAuthFilter(store, jwtVerifier, "/health", "/.well-known");
  }

  // --- no-op / open-server cases ---

  @Test
  void disabledStore_noJwtVerifier_proceedsWithoutToken() {
    FakeContext ctx = new FakeContext(null, "/mcp");
    FakeChain chain = new FakeChain();

    filter(new TokenStore(null)).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
  }

  // --- static token cases ---

  @Test
  void permittedHealthPath_proceedsWithoutToken() {
    FakeContext ctx = new FakeContext(null, "/health/liveness");
    FakeChain chain = new FakeChain();

    filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void validStaticBearer_proceedsAndBindsPrincipal() {
    FakeContext ctx = new FakeContext("Bearer secret", "/mcp");
    FakeChain chain = new FakeChain();

    filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
    assertThat(ctx.attributes.get(BearerAuthFilter.ATTR_PRINCIPAL)).isEqualTo("claude");
  }

  @Test
  void wrongStaticToken_noJwtVerifier_throws401() {
    FakeContext ctx = new FakeContext("Bearer wrong", "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class)
        .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(chain.proceeded).isFalse();
  }

  @Test
  void missingAuthorizationHeader_throws401() {
    FakeContext ctx = new FakeContext(null, "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class)
        .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(chain.proceeded).isFalse();
  }

  @Test
  void nonBearerHeader_throws401() {
    FakeContext ctx = new FakeContext("Basic abc", "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class)
        .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(chain.proceeded).isFalse();
  }

  // --- JWT verifier cases ---

  @Test
  void validJwt_withNoStaticTokens_proceedsAndBindsSub() {
    JwtVerifier verifier = alwaysVerifies("user@example.com");
    FakeContext ctx = new FakeContext("Bearer some.jwt.token", "/mcp");
    FakeChain chain = new FakeChain();

    filter(new TokenStore(null), verifier).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
    assertThat(ctx.attributes.get(BearerAuthFilter.ATTR_PRINCIPAL)).isEqualTo("user@example.com");
  }

  @Test
  void staticTokenMatchesFirst_jwtNotAttempted() {
    // Token matches the static store — verifier would throw if called, but it shouldn't be.
    JwtVerifier verifier = alwaysThrows();
    FakeContext ctx = new FakeContext("Bearer secret", "/mcp");
    FakeChain chain = new FakeChain();

    filter(new TokenStore("agent:secret"), verifier).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
    assertThat(ctx.attributes.get(BearerAuthFilter.ATTR_PRINCIPAL)).isEqualTo("agent");
  }

  @Test
  void invalidJwt_noMatchingStaticToken_throws401WithWwwAuthenticate() {
    JwtVerifier verifier = alwaysThrows();
    FakeContext ctx = new FakeContext("Bearer bad.jwt", "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore(null), verifier).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class)
        .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(chain.proceeded).isFalse();
    assertThat(ctx.responseHeaders.get("WWW-Authenticate"))
        .startsWith("Bearer realm=\"ebean-insight-mcp\", resource_metadata=\"https://")
        .contains("/.well-known/oauth-protected-resource");
  }

  @Test
  void noToken_jwtConfigured_throws401WithWwwAuthenticate() {
    JwtVerifier verifier = alwaysVerifies("sub");
    FakeContext ctx = new FakeContext(null, "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore(null), verifier).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class)
        .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(ctx.responseHeaders.get("WWW-Authenticate"))
        .contains("resource_metadata=");
  }

  @Test
  void wwwAuthenticate_usesXForwardedProto_whenPresent() {
    // Simulate an ingress that terminates TLS: raw connection is http but
    // X-Forwarded-Proto says https.
    JwtVerifier verifier = alwaysThrows();
    FakeContext ctx = new FakeContext("Bearer bad.jwt", "/mcp")
        .withRequestHeader("X-Forwarded-Proto", "https");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore(null), verifier).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class);
    assertThat(ctx.responseHeaders.get("WWW-Authenticate"))
        .startsWith("Bearer realm=\"ebean-insight-mcp\", resource_metadata=\"https://");
  }

  @Test
  void wwwAuthenticate_fallsBackToCtxScheme_whenNoForwardedProto() {
    JwtVerifier verifier = alwaysThrows();
    // FakeContext returns "https" for ctx.scheme() by default — no forwarded header.
    FakeContext ctx = new FakeContext("Bearer bad.jwt", "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore(null), verifier).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class);
    assertThat(ctx.responseHeaders.get("WWW-Authenticate"))
        .startsWith("Bearer realm=\"ebean-insight-mcp\", resource_metadata=\"https://");
  }

  @Test
  void wellKnownPath_permittedWithJwtConfigured() {
    JwtVerifier verifier = alwaysVerifies("sub");
    FakeContext ctx = new FakeContext(null, "/.well-known/oauth-protected-resource");
    FakeChain chain = new FakeChain();

    filter(new TokenStore(null), verifier).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void noToken_staticOnlyConfig_noWwwAuthenticate() {
    // Static-only config: no WWW-Authenticate header (not an OAuth2 server).
    FakeContext ctx = new FakeContext(null, "/mcp");
    FakeChain chain = new FakeChain();

    assertThatThrownBy(() -> filter(new TokenStore("a:b")).filter(ctx.asContext(), chain))
        .isInstanceOf(HttpResponseException.class);
    assertThat(ctx.responseHeaders).doesNotContainKey("WWW-Authenticate");
  }

  // --- helpers ---

  private static JwtVerifier alwaysVerifies(String sub) {
    return new JwtVerifier() {
      @Override
      public void verify(io.avaje.oauth2.core.jwt.SignedJwt jwt) {}
      @Override
      public AccessToken verifyAccessToken(String token) {
        return new AccessToken(sub, null, null, 0, null, 0, 0, 0, null, null);
      }
    };
  }

  private static JwtVerifier alwaysThrows() {
    return new JwtVerifier() {
      @Override
      public void verify(io.avaje.oauth2.core.jwt.SignedJwt jwt) {}
      @Override
      public AccessToken verifyAccessToken(String token) throws JwtVerifyException {
        throw new JwtVerifyException("bad token");
      }
    };
  }

  private static final class FakeChain implements HttpFilter.FilterChain {
    boolean proceeded;

    @Override
    public void proceed() {
      proceeded = true;
    }
  }

  /**
   * Minimal {@link Context} via dynamic proxy handling header reads/writes,
   * path, and attributes (Mockito cannot mock the Jex Context interface under
   * JPMS on recent JDKs).
   */
  private static final class FakeContext {
    final Map<String, Object> attributes = new HashMap<>();
    final Map<String, String> responseHeaders = new HashMap<>();
    private final Map<String, String> requestHeaders = new HashMap<>();
    private final String authHeader;
    private final String path;

    FakeContext(String authHeader, String path) {
      this.authHeader = authHeader;
      this.path = path;
    }

    FakeContext withRequestHeader(String name, String value) {
      requestHeaders.put(name, value);
      return this;
    }

    Context asContext() {
      InvocationHandler handler = (p, method, args) -> switch (method.getName()) {
        case "header" -> {
          if (args.length == 1) {
            // read request header
            String name = (String) args[0];
            if ("Authorization".equals(name)) yield authHeader;
            yield requestHeaders.get(name);
          } else {
            // write response header
            responseHeaders.put((String) args[0], (String) args[1]);
            yield p;
          }
        }
        case "path" -> path;
        case "scheme" -> "https";
        case "host" -> "mcp.example.com";
        case "attribute" -> {
          attributes.put((String) args[0], args[1]);
          yield p;
        }
        case "toString" -> "FakeContext";
        case "hashCode" -> System.identityHashCode(p);
        case "equals" -> p == args[0];
        default -> throw new UnsupportedOperationException("Unexpected Context call: " + method.getName());
      };
      return (Context) Proxy.newProxyInstance(
          Context.class.getClassLoader(), new Class<?>[]{Context.class}, handler);
    }
  }
}
