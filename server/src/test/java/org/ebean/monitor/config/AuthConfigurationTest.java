package org.ebean.monitor.config;

import io.avaje.jex.Jex;
import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;
import io.avaje.jex.spi.JexPlugin;
import io.avaje.oauth2.core.data.AccessToken;
import io.avaje.oauth2.core.jwt.JwtVerifier;
import io.avaje.oauth2.core.jwt.JwtVerifyException;
import io.avaje.oauth2.core.jwt.SignedJwt;
import io.avaje.oauth2.jex.jwtfilter.JwtAuthFilter;
import org.ebean.monitor.web.ApiKeyValidator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the auth wiring: the permit-list applied to the JwtAuthFilter
 * (/health + /api/ingest open, everything else enforced) and that the
 * JexPlugin registers the filter. Filter behaviour itself is covered by
 * avaje-oauth2-jex-jwtfilter's own tests.
 */
class AuthConfigurationTest {

  private static final String VALID_TOKEN = "good-token";

  private static AccessToken accessToken() {
    return new AccessToken("sub1", "access", "insight/read", 0L,
      "issuer", 0L, 0L, 1, "jti1", "client123");
  }

  private JwtAuthFilter filter() {
    return filter(new ApiKeyValidator(List.of()));
  }

  private JwtAuthFilter filter(ApiKeyValidator apiKeyValidator) {
    return new AuthConfiguration().jwtAuthFilter(new FakeVerifier(), apiKeyValidator);
  }

  @Test
  void healthPermitted_withoutToken() {
    FakeChain chain = new FakeChain();
    filter().filter(context(null, "/health/liveness"), chain);
    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void ingestPermitted_withoutToken() {
    FakeChain chain = new FakeChain();
    filter().filter(context(null, "/api/ingest"), chain);
    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void v1Protected_withoutToken_throws401() {
    FakeChain chain = new FakeChain();
    assertThatThrownBy(() -> filter().filter(context(null, "/v1/apps"), chain))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(chain.proceeded).isFalse();
  }

  @Test
  void uiProtected_withoutToken_throws401() {
    FakeChain chain = new FakeChain();
    assertThatThrownBy(() -> filter().filter(context(null, "/"), chain))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
  }

  @Test
  void v1Permitted_withValidToken() {
    FakeChain chain = new FakeChain();
    filter().filter(context("Bearer " + VALID_TOKEN, "/v1/apps"), chain);
    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void invalidToken_throws401() {
    FakeChain chain = new FakeChain();
    assertThatThrownBy(() -> filter().filter(context("Bearer nope", "/v1/apps"), chain))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
  }

  @Test
  void apiKey_acceptedAsBearer_onV1_skipsJwt() {
    FakeChain chain = new FakeChain();
    // FakeVerifier would reject "api-secret"; acceptance proves the api-key path won.
    filter(new ApiKeyValidator(List.of("api-secret")))
      .filter(context("Bearer api-secret", "/v1/apps"), chain);
    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void apiKey_enabled_validJwtStillWorks() {
    FakeChain chain = new FakeChain();
    filter(new ApiKeyValidator(List.of("api-secret")))
      .filter(context("Bearer " + VALID_TOKEN, "/v1/apps"), chain);
    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void apiKey_enabled_wrongTokenAndInvalidJwt_throws401() {
    FakeChain chain = new FakeChain();
    assertThatThrownBy(() -> filter(new ApiKeyValidator(List.of("api-secret")))
      .filter(context("Bearer nope", "/v1/apps"), chain))
      .isInstanceOf(HttpResponseException.class)
      .satisfies(e -> assertThat(((HttpResponseException) e).status()).isEqualTo(401));
    assertThat(chain.proceeded).isFalse();
  }

  @Test
  void apiKey_enabled_permittedPathStillOpenWithoutToken() {
    FakeChain chain = new FakeChain();
    filter(new ApiKeyValidator(List.of("api-secret")))
      .filter(context(null, "/health/liveness"), chain);
    assertThat(chain.proceeded).isTrue();
  }

  @Test
  void authFilterPlugin_registersFilter() {
    JwtAuthFilter authFilter = filter();
    JexPlugin plugin = new AuthConfiguration().authFilterPlugin(authFilter);

    CapturingJex jex = new CapturingJex();
    plugin.apply(jex.asJex());

    assertThat(jex.registered).isSameAs(authFilter);
  }

  private static Context context(String authHeader, String path) {
    InvocationHandler handler = (p, method, args) -> switch (method.getName()) {
      case "header" -> "Authorization".equals(args[0]) ? authHeader : null;
      case "path" -> path;
      case "attribute" -> p;
      case "toString" -> "FakeContext";
      case "hashCode" -> System.identityHashCode(p);
      case "equals" -> p == args[0];
      default -> throw new UnsupportedOperationException("Unexpected Context call: " + method.getName());
    };
    return (Context) Proxy.newProxyInstance(
      Context.class.getClassLoader(), new Class<?>[]{Context.class}, handler);
  }

  /** JwtVerifier returning a fixed token only for {@link #VALID_TOKEN}, else throws. */
  private static final class FakeVerifier implements JwtVerifier {
    @Override
    public void verify(SignedJwt jwt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AccessToken verifyAccessToken(String accessToken) throws JwtVerifyException {
      if (!VALID_TOKEN.equals(accessToken)) {
        throw new JwtVerifyException("invalid token");
      }
      return accessToken();
    }
  }

  private static final class FakeChain implements HttpFilter.FilterChain {
    boolean proceeded;

    @Override
    public void proceed() {
      proceeded = true;
    }
  }

  /** Captures the HttpFilter registered via Jex.filter(...). */
  private static final class CapturingJex {
    HttpFilter registered;
    private final Jex proxy;

    CapturingJex() {
      InvocationHandler handler = (p, method, args) -> {
        if ("filter".equals(method.getName()) && args != null && args.length == 1
          && args[0] instanceof HttpFilter f) {
          registered = f;
          return p;
        }
        return switch (method.getName()) {
          case "toString" -> "CapturingJex";
          case "hashCode" -> System.identityHashCode(p);
          case "equals" -> p == args[0];
          default -> p;
        };
      };
      this.proxy = (Jex) Proxy.newProxyInstance(
        Jex.class.getClassLoader(), new Class<?>[]{Jex.class}, handler);
    }

    Jex asJex() {
      return proxy;
    }
  }
}
