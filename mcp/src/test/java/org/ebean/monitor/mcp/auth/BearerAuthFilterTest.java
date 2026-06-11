package org.ebean.monitor.mcp.auth;

import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BearerAuthFilterTest {

  private static BearerAuthFilter filter(TokenStore store) {
    return new BearerAuthFilter(store, "/health");
  }

  @Test
  void disabledStore_proceedsWithoutToken() {
    FakeContext ctx = new FakeContext(null, "/mcp");
    FakeChain chain = new FakeChain();

    filter(new TokenStore(null)).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void permittedHealthPath_proceedsWithoutToken() {
    FakeContext ctx = new FakeContext(null, "/health/liveness");
    FakeChain chain = new FakeChain();

    filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void validBearer_proceedsAndBindsPrincipal() {
    FakeContext ctx = new FakeContext("Bearer secret", "/mcp");
    FakeChain chain = new FakeChain();

    filter(new TokenStore("claude:secret")).filter(ctx.asContext(), chain);

    assertThat(chain.proceeded).isTrue();
    assertThat(ctx.attributes.get(BearerAuthFilter.ATTR_PRINCIPAL)).isEqualTo("claude");
  }

  @Test
  void wrongToken_throws401() {
    FakeContext ctx = new FakeContext("Bearer nope", "/mcp");
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

  private static final class FakeChain implements HttpFilter.FilterChain {
    boolean proceeded;

    @Override
    public void proceed() {
      proceeded = true;
    }
  }

  /**
   * Minimal {@link Context} via dynamic proxy handling only header/path/attribute
   * (Mockito cannot mock the Jex Context interface under JPMS on recent JDKs).
   */
  private static final class FakeContext {
    final Map<String, Object> attributes = new HashMap<>();
    private final Context proxy;

    FakeContext(String authHeader, String path) {
      InvocationHandler handler = (p, method, args) -> switch (method.getName()) {
        case "header" -> "Authorization".equals(args[0]) ? authHeader : null;
        case "path" -> path;
        case "attribute" -> {
          attributes.put((String) args[0], args[1]);
          yield p;
        }
        case "toString" -> "FakeContext";
        case "hashCode" -> System.identityHashCode(p);
        case "equals" -> p == args[0];
        default -> throw new UnsupportedOperationException("Unexpected Context call: " + method.getName());
      };
      this.proxy = (Context) Proxy.newProxyInstance(
          Context.class.getClassLoader(), new Class<?>[]{Context.class}, handler);
    }

    Context asContext() {
      return proxy;
    }
  }
}
